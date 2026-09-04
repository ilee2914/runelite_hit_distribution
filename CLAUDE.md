# RuneLite Hit Stats Plugin

## Project Goal

Record every hit the player deals, keyed by the full combat context it was dealt under (gear,
the levels that affect damage, the prayers that affect damage, style, spell, special attack,
target NPC and the overhead prayer that NPC was using), and show the damage
distribution, accuracy, splash rate, DPS and wasted ticks in a side panel filterable by monster,
style, target prayer and any worn equipment slot. An opt-in upload shares those statistics with a
community server so the panel can draw everyone else's distribution as a line across the player's
own bars; it is off by default and sends nothing until it is turned on.

This plugin only observes. It reads hitsplats, animations, graphics, varbits and containers, and
writes a local JSON file. It never sends input, queues a menu action, or changes game state.

The server is a separate, private repository (`hit_stats_server`, a sibling of this one).
Its design, the wire format and the operational rules live in `../server_plan.md`, which is the
specification for anything under the `sync` package. Read it before changing the payload.

## Naming

Intended for the Plugin Hub, so every identifier is chosen to be unique there:

| Thing | Value |
| --- | --- |
| Hub name / jar / `rootProject.name` | `hit-stats` |
| Display name | Hit Stats |
| Config group | `hitstats` |
| Package | `com.github.ilee2.hitstats` |
| Data directory | `~/.runelite/hit-stats/` |

A **different** Hub plugin already owns the name `damage-history` (QuestingPet/DamageHistory,
class `com.damagehistory.DamageHistoryPlugin`). It tracks per-player damage totals for you and
your party and, by its own README, cannot see 0-damage hitsplats or splashes and does no gear,
stat, DPS or wasted-tick tracking. Do not reuse any of its identifiers.

## Architecture

| Class | Responsibility |
| --- | --- |
| `HitStatsPlugin` | Event wiring, panel and nav button, periodic panel refresh |
| `HitStatsConfig` | Config panel |
| `CombatTracker` | Client-facing logic: snapshots contexts on attack animations, feeds hits and splashes through the matcher, tracks fights, writes to the store |
| `AttackMatcher` | Pure tick arithmetic pairing attack animations with hitsplats and splashes |
| `PendingAttack` | An animation waiting for its hit, with the context snapshot |
| `CombatContext` | Immutable snapshot; `getKey()` is a SHA-256 prefix of its fields |
| `ContextStats` | Per-context histogram and counters |
| `KillRecord` | One fight against one NPC instance |
| `HitRecord` | One logged hitsplat, pointing back at its context |
| `HistoryData` | Root of the JSON file: names, contexts, fights |
| `HitStatsStore` | Synchronized in-memory data plus load/save; builds `Aggregate` and `FilterOptions` |
| `Aggregate` | Contexts and fights folded into one histogram and derived statistics |
| `HistoryFilter` / `FilterOptions` | What the panel is filtering on, and what it can filter on |
| `AttackStyleResolver` | Weapon category + style varps → combat style, style name, Rapid |
| `AutocastSpell` | Autocast varbit value → spell name |
| `OverheadPrayer` | Overhead icon → the combat styles it protects against |
| `DamagePrayers` | Which of the player's prayers belong in the context |
| `OverheadPrayerReader` | NPC overhead sprite arrays → `OverheadPrayer` set |
| `ui.HitStatsPanel` | Sidebar: filters, stats grid, histogram, breakdown |
| `ui.FilterSelect` | Search box plus suggestion popup, standing in for a dropdown |
| `ui.HistogramPanel` | Hand-painted bar chart for the whole filtered set |
| `ui.EquipmentPanel` | Worn gear as item icons in the game's equipment layout |
| `ui.EquipmentFilterPanel` | The same layout, clickable, as the per-slot gear filter |
| `ui.EquipmentLayout` | The slot arrangement both of those share |
| `LevelMatch` | How closely a community comparison matches the player's levels |
| `sync.SyncTransport` | The two HTTP calls, behind an interface so the rest is testable |
| `sync.OkHttpTransport` | The real one, over RuneLite's injected `OkHttpClient` |
| `sync.UploadBatch` | One upload, exactly as it goes over the wire |
| `sync.CommunitySync` | When to upload, the failure policy, the panel's status line |
| `sync.CommunityQuery` | A community lookup as a URL, built from the panel's own filter |
| `sync.CommunityAggregate` | A community answer, with the same derived statistics as `Aggregate` |
| `sync.CommunityClient` | Fetches and caches answers off the client and Swing threads |

### Attribution model

1. `AnimationChanged` on the local player while interacting with an NPC, **and** a once-a-tick
   sample of the animation for attacks that fire no event (held or re-set to the same id) →
   snapshot a
   `CombatContext`, including the target's overhead prayers and whether one of them protects
   against the style being used, and offer a `PendingAttack` to the matcher. The matcher rejects
   animations inside the cooldown of the last committed attack (blocks, eats) using the previous
   attack's speed, which is how the game's own cooldown works. The tracker asks
   `matcher.inCooldown(tick)` **before** building the snapshot, because a held animation lands
   here every tick. A manually cast spell is consumed only by an attack the matcher accepts; a
   block animation while waiting for the cast must not eat it.
2. `HitsplatApplied` with `isMine()` on an NPC → `matchHit`. Same-target pending attacks are
   preferred; an already-resolved attack only accepts more hits on the same tick (multi-hit
   weapons); otherwise any unresolved attack takes the hit (area spill). A hit that arrives before
   its animation in the same tick is held as an orphan and retried on `GameTick`; still-unmatched
   orphans are counted as unattributed and kept out of the histogram.
3. `GraphicChanged` on an NPC showing spot anim 85 (`SpotanimID.FAILEDSPELL_IMPACT`) → `matchSplash`,
   which only pairs with a magic attack of ours on that NPC. The graphic has no owner, so the
   attack stays weakly open (`PendingAttack.resolvedBySplash`): a later hitsplat of ours on the
   same NPC, with no newer open attack on it to claim the hit, means the splash was another
   player's, and the tracker takes it back (`store.undoSplash`). Spill hits on other NPCs never
   trigger the take-back.
4. The first match of an attack records it: wasted ticks = gap since the previous recorded attack
   minus that attack's speed, when the gap is within the idle threshold; active ticks = the gap,
   or the attack's own speed when there is no recent previous attack.
5. `ActorDeath` (or a despawn with `isDead()`) is noted on the fight and acted on at the end of
   the tick in `closeDeadFights`, after `retryOrphans`, because the killing hitsplat lands on the
   same tick and nothing promises it is delivered first. The hits recorded on the death tick are
   marked as killing blows (`store.markKillingBlow`, which moves them from `counts` into
   `killCounts` and flags the `HitRecord`), then the fight closes and writes a `KillRecord`. A
   despawn without a death, or the 100-tick timeout, closes it at once with `killed = false`.

### Client facts this relies on

- `Hitsplat.isMine()` covers `DAMAGE_ME`, `DAMAGE_MAX_ME`, `BLOCK_ME` and colour variants. A miss
  is `BLOCK_ME` with amount 0. Max hits are the `DAMAGE_MAX_ME*` types.
- Splash is spot animation **85**. There is no hitsplat for a splash.
- Autocast spell is **varbit** `AUTOCAST_SPELL` (276); the id → name table is in `AutocastSpell`
  (same values the Hub's autocast-utilities plugin uses).
- Special attack: `SA_ENERGY` (varp 300) dropping on the animation tick, or `SA_ATTACK` (varp
  301) still set when the animation fires.
- Manual casts: `MenuOptionClicked` with `WIDGET_TARGET_ON_NPC` and option `Cast`; the spell name
  is the part of the menu target before ` -> `.
- Equipment is `InventoryID.WORN` (94), slots in `EquipmentInventorySlot` order; weapon is slot 3.
- Attack speed comes from `ItemManager.getItemStats(id).getEquipment().getAspeed()`; unarmed is 4.
- Weapon category and style: same cache walk as the prayer alert plugin (enum 3908, param 1407).
- NPC overheads are parallel arrays, `NPC#getOverheadArchiveIds()` and
  `NPC#getOverheadSpriteIds()`; `HeadIcon` is players only. Archive **440** holds the prayer
  icons and an unset archive id (-1) means that same archive. Sprite index follows `HeadIcon`
  order: melee, missiles, magic, retribution, smite, redemption, the three pairs, protect-all,
  wrath, soul split, then the three deflect icons.
- `Client.isPrayerActive` is deprecated; read `client.getVarbitValue(prayer.getVarbit())` instead.

## Building & Running In-Game

**Any change to plugin source or resources requires a rebuild before the running client will
show it.** The client loads a packaged jar, not the working tree.

```
.\gradlew.bat jar
.\gradlew.bat installSideload
```

Then relaunch RuneLite with `--developer-mode` (the `Launch-RuneLiteDev.ps1` script in the parent
folder). `.\gradlew.bat test` runs the unit tests; keep them green.

Notes:

- `compileJava` is **not** enough — it never produces a jar.
- Fully exit RuneLite before rebuilding; a running client holds the sideloaded jar open.
- The history file is `~/.runelite/hit-stats/<player>.json`. Delete it, or use the panel's
  Clear button, to start over.

## Rules

1. **Journal**: Keep `JOURNAL.md` updated with all code changes, features added, and decisions
   made.
2. **Rebuild After Changes**: After editing any plugin source or resource, run
   `.\gradlew.bat jar` so the sideloaded jar is current. Never report a change as testable
   in-game without it.
3. **Code Style**: Follow RuneLite plugin conventions — `@Inject`, `@Subscribe`, Lombok
   (`@Slf4j`, `@Getter`), Guice DI, tabs for indentation, braces on their own line.
4. **No automation**: this plugin only ever observes and records. Never add anything that sends
   input, queues a menu action, or changes game state.
5. **The network is opt-in in both directions, and never on a hot path**: with the two Community
   switches off the plugin makes no request of any kind. Uploads need `uploadEnabled`; the
   community chart needs `showCommunity`, which is **also off by default** because fetching an
   average is still contacting a server, and a plugin that phones home before anyone asks it to
   is what Hub review exists to catch. Both descriptions state exactly what is sent. The hidden
   `serverUrl` override is restricted to localhost and this plugin's own subdomain, so it cannot
   be turned into a redirect to somewhere else. Never
   send the character name, account hash, world, position, chat, or anything about another
   player; the uploader id is a random UUID in the history file. Use the **injected**
   `OkHttpClient` (through `sync.SyncTransport`), never a new one. Uploads happen on the
   executor, at most one at a time, on the same occasions the file is written: a timer, logout,
   client shutdown, and a catch-up after login. Never per attack, never on the client thread,
   never blocking Swing. Counters sent are cumulative, never deltas, so a retry cannot double
   count. `CommunitySync.DEFAULT_BASE_URL` is empty until the Worker is deployed, and an empty
   base URL disables every request whatever the config says.
6. **Keep the matcher pure**: `AttackMatcher` must stay free of client types so it remains unit
   testable. Client access belongs in `CombatTracker`.
7. **Package**: `com.github.ilee2.hitstats` — all classes live under this package.
8. **Java Version**: Target Java 11 (RuneLite requirement).
9. **Dependencies**: Only libraries available through RuneLite's client dependency. No SQLite,
   no charting library; the Hub restricts dependencies and native code.
10. **File format**: Bump `HistoryData.CURRENT_VERSION` and handle the old shape in
    `HistoryData.upgrade` if fields change. Player files are not to be silently discarded.
    Currently at version 8. Where the *shape* of a stored record changes (version 6 dropped
    Defence from the level arrays), the record is migrated in place via `ContextStats.migrate`
    and a key-preserving `CombatContext` copy.
    **Version 8 recomputes every key and merges the records that collide.** Versions 3, 4 and 6
    each removed something from the key but left existing records under the key they were written
    with, so a setup recorded before one of those changes sat frozen and never received another
    hit; one real file held 983 records that were 140 setups. Any future change to what
    `CombatContext.computeKey` hashes must do the same: recompute, merge with
    `ContextStats.mergeFrom`, and remap `KillRecord.contextKey` and `HitRecord.contextKey`, or
    fights and logged hits will point at keys nothing answers to.
    A key change is also a **server** event: every row a player has uploaded is keyed the old way,
    so bump the server's `min_key_version` on the same day (see `../server_plan.md`, section 8.5).
    `HitStatsStore.load` reads the file before it sets the player name, and `read` catches
    every RuntimeException, so a failed read can never leave an empty store that the autosave then
    writes over the file.
11. **Identifiers are frozen once published**: the config group and data directory cannot change
    after release without losing people's settings and history. The plugin is **not** on the Hub
    yet, so a rename is still cheap; it must happen before release, and it must move the config
    group, the package, the data directory, the repository and the Worker name together.
12. **Only damage-affecting state belongs in the context**: `DamagePrayers` excludes prayers
    that cannot change the damage dealt, and `CombatContext.SKILL_NAMES` excludes Hitpoints,
    Prayer and Defence. Anything that drifts during a fight but cannot change the damage will otherwise turn
    one setup into a stream of one-attack rows; HP alone accounted for a 9x inflation in a real
    session. The prayer list is an exclusion list on purpose, so an unrecognised prayer is kept
    rather than silently merged. Wrongly keeping something costs a row; wrongly dropping it
    corrupts an average.
15. **Three bodies of records, one write path**: the store holds the file's `HistoryData` plus
    an in-memory session and an in-memory fight window, and every write goes through
    `all()` into each of them. Reads take a `HistoryScope`. Never write to `data` alone; the
    session and fight views would silently drift from the file.
13. **New `CombatContext` fields must be null-safe on read**: Gson bypasses the constructor, so
    any field added later loads as null from an existing history file. Give lists an explicit
    null-safe getter. Skipping this crashed the panel on the first upgrade, every two seconds,
    for anyone with a file from a previous version.
14. **Keep sources pure ASCII**: write any non-ASCII character as a `\uXXXX` escape.
    Windows PowerShell 5.1 `Get-Content` reads a BOM-less UTF-8 file as ANSI, so editing a source
    file through a PowerShell read/write round-trip silently corrupts it. This already happened
    once to the panel's middle-dot separators. Prefer the file tools or Python for edits.
