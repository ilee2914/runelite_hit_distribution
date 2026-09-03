# Journal

## 2026-09-02 — Initial build

### Decisions

- **Legality**: passive tracking of own hitsplats, gear and stats is Hub-legal; precedents are
  the built-in DPS Counter, Damage Counter, PvP Performance Tracker and Combat Logger. A future
  comparison server must be opt-in with a data warning.
- **Attribute at attack time, not hitsplat time.** Projectiles land one to three ticks after the
  animation and players switch gear in that window. The context is snapshotted on
  `AnimationChanged` and the hitsplat is matched back to it.
- **Histogram per context instead of a row per hit.** A context is gear + boosted/real levels +
  prayers + style + spell + spec + NPC id. Two 10s in the same context are one increment. Fights
  are summarised per NPC instance so kill counts, kill times and DPS survive without per-hit rows.
- **Group by NPC id, display by name.** Ids are the key; the file carries an id → name and combat
  level map learned on first sight from the transformed composition. A config toggle splits by id
  for bosses whose phases share a name.
- **No SQLite, no chart library.** The Hub restricts dependencies and bans native code. Gson JSON
  under `~/.runelite/hit-distribution/`, and a hand-painted Swing histogram.

### Implementation notes

- `AttackMatcher` is pure so it can be tested: cooldown rejection of block/eat animations,
  retroactive dropping of animations inside a committed attack's cooldown, same-target
  preference, same-tick multi-hit acceptance, area spill onto other NPCs, splash pairing only
  with own magic attacks, expiry with an unmatched counter.
- Hitsplats can arrive ahead of the animation in the same tick; they are held as orphans and
  retried on `GameTick`. Still-unmatched ones are counted as "unattributed" (vengeance, recoil,
  missed animations) and never enter the distribution.
- Spell identification: autocast **varbit** 276 → name table copied from the Hub's
  autocast-utilities plugin; manual casts come from the `Cast` menu target. The first compile
  failed because I had it as a varp.
- Attack speed: item stats, minus one for Rapid; spells fixed at 5, harmonised nightmare staff 4
  on the standard spellbook.
- Special attacks: spec energy varp dropping on the animation tick flags the pending attack; if
  the varbit event lands after the animation the context is swapped for its `asSpecial()` copy
  before it is recorded.
- `Client.isPrayerActive` is deprecated in 1.12.35; prayers are read as
  `client.getVarbitValue(prayer.getVarbit())`.
- The store is `synchronized` and hands the panel deep copies (`Aggregate`) so the Swing thread
  never reads arrays the client thread is growing. The panel polls a revision counter every two
  seconds rather than subscribing to each write.
- Panel uses `PluginPanel(true)` so RuneLite wraps it in a scroll pane; the breakdown list can be
  longer than the sidebar.
- Build: 25 unit tests across `AttackMatcherTest`, `ContextStatsTest`, `HitDistributionStoreTest`
  (aggregation maths, filters, options, Gson round-trip). First scaffold pass through a shell
  heredoc mangled the `\\.` in build.gradle; the file is now copied from the prayer alert build
  with names substituted.

### Named for publication

Originally built as "Damage History", which collides with an existing Hub plugin: `damage-history`
(QuestingPet/DamageHistory, class `com.damagehistory.DamageHistoryPlugin`), which the client log
shows was installed and removed here on 2026-09-02. A shared config group would have mixed the two
plugins' settings, and the Hub requires a unique plugin name.

Renamed everything to **Hit Distribution** — the distribution chart is what distinguishes this
plugin, and `hit-distribution` was free among the 2,513 names in the plugin-hub repository. The
project folder is `runelite_hit_distribution`, the jar and Hub name are `hit-distribution`, the
package is `com.github.ilee2.hitdistribution`, the config group is `hitdistribution`, and data
lives in `~/.runelite/hit-distribution/`. The config group and data directory are effectively
frozen once published.

An interim config group of `ilee2hitdistribution` was used briefly to dodge the collision and has
been dropped now that the whole plugin is uniquely named.

### Not yet verified in-game

Everything above is compiled and unit tested but has not been exercised in a live client. Things
to watch on the first session with debug logging on:

- Whether melee hitsplats arrive on the same tick as the animation or the next (the matcher
  accepts both).
- Whether `SA_ATTACK` is still 1 when the spec animation fires, or only the energy drop catches it.
- Ranged at long distance may need the hit window above 6 ticks.
- Powered staves: confirm the splash graphic pairs and that `AttackStyleResolver` reads them as
  magic with no spell.

## 2026-09-02 (later) - Target prayer, and an encoding bug

### Target's overhead prayer is now part of the context

A monster praying against the style you are using takes far less damage from it, which dragged
every average down and hid what the gear actually does. `OverheadPrayer` and
`OverheadPrayerReader` are ported from the prayer alert plugin: NPC overheads come from the
parallel `getOverheadArchiveIds()` / `getOverheadSpriteIds()` arrays, archive 440, sprite index in
`HeadIcon` order.

`CombatContext` gained `targetOverheads` and `styleProtected`, both in the key, so praying and
non-praying attacks form separate distributions. `styleProtected` is stored rather than derived so
a future change to the icon table cannot rewrite history. Only overheads that protect against the
style used count; Retribution, Smite, Redemption, Wrath and Soul Split are recorded but do not
mark an attack as blocked, and `CombatStyle.UNKNOWN` never counts as blocked (that would move real
hits into the protected bucket).

The panel gained a **Target** filter (All / Not protecting / Praying against me), an "Into
protection" stat, and a red line on any breakdown row whose target was praying.

`CombatContext` moved to a Lombok `@Builder(toBuilder = true)`; the constructor had reached
fifteen positional arguments and `asSpecial()` is now `toBuilder().special(true).build()`.

File format is version 2. Records written under version 1 keep their old keys and are not
discarded, so they simply sit alongside newer ones rather than merging.

### The "A with a hat" symbol

Reported in the panel next to the attack counts. It was mojibake I introduced: I had edited
`HitDistributionPanel.java` with a PowerShell `Get-Content -Raw` / `WriteAllText` round-trip, and
PowerShell 5.1 reads a BOM-less UTF-8 file as ANSI, so every U+00B7 middle dot became
U+00C2 U+00B7. Repaired, and all nine separators are now written as escapes so the source is pure
ASCII and no future round-trip can break them. Added as rule 12 in CLAUDE.md.

Tests are up to 37 (new `OverheadPrayerTest`, plus context-key and filter coverage for protection).

## 2026-09-02 (evening) - Upgrade crash, missed attacks, searchable and cascading filters

### The panel was dying every two seconds

Reported as "we're not tracking non-boss kills now". The data said otherwise: the history file
already held Corrupted Rat (level 33) with 5 attacks and 2 kills, so non-boss monsters were being
recorded. What was broken was the panel.

The client log had a `NullPointerException` in `contextRow` on every refresh. Cause: the client
restarted at 14:34 onto the build that added `targetOverheads`, and read a file written by the
previous build, which had no such field. Gson bypasses the constructor, so the list came back
**null**, and `getTargetOverheads().isEmpty()` threw. `refresh()` died before `revalidate()`, so
the panel showed stale content for the rest of the session.

Fixed with explicit null-safe getters for `prayers` and `targetOverheads`, plus a test that
deserialises an older-format context and asserts the lists come back empty. Added as rule 12 in
CLAUDE.md, because this will happen again the next time a field is added.

### Attacks that fire no animation event

Corrupted Bear was in the file's NPC name map, meaning it had been engaged, but had no context at
all, and 14 hitsplats were logged as unattributed. The tracker only listened to `AnimationChanged`,
but an animation held across ticks, or re-set to the id it already had, fires no event, so those
attacks were invisible and their hits had nothing to attach to.

The animation is now also sampled once a tick (`sampleHeldAnimation`), skipping the tick if the
event already produced an attack, since that path is the one carrying a manually cast spell. False
positives are free: the matcher drops anything inside the previous attack's cooldown, and an attack
that never matches a hitsplat or splash is never recorded. This is the same reasoning the prayer
alert plugin uses for sampling on the tick as well as on the event.

### Filters

- `HitDistributionStore.options` now takes the current filter and builds each list with its own
  dimension ignored and the others applied. Choosing a weapon narrows the attack list to what was
  used with it; no box ever removes its own options, so a dead-end combination still offers a way
  out.
- Search boxes (`IconTextField`) under the monster and weapon lists, shown only once a list passes
  six entries. The current selection always stays in the list even when it does not match the
  search, so typing never silently changes the numbers on screen.

Tests are at 39.

## 2026-09-02 (late) - Fewer prayer combinations, and a lighter breakdown

### Only damage-affecting prayers go in the context

Protection and defence prayers change nothing about the damage you deal, but they were in the
context key, so flicking Protect from Melee at a boss doubled the number of contexts for one set
of gear. `DamagePrayers` now filters them out before the snapshot is built.

It is written as an **exclusion** list rather than a whitelist. Only prayers that certainly cannot
change the damage dealt are dropped: the three defence prayers, the three overhead protections, the
restore and utility ones, Retribution, Redemption and Smite, and the Ruinous equivalents of those.
Everything else, including Ruinous powers whose exact effects I am not sure of, is kept. Wrongly
keeping a prayer costs one extra row in the breakdown; wrongly dropping one blends two genuinely
different distributions into a single misleading average.

Accuracy prayers count as damage-affecting: they change how often a zero is rolled, which is the
shape of the distribution.

File format is version 3. Contexts recorded under versions 1 and 2 keep their old keys.

### The breakdown is now a damage history

The rows had six lines each and were unreadable at a glance. Each row is now: what it was
(weapon and attack), how many attacks are behind it, a red line if the target was praying against
that style, and a compact bar chart of the damage (`DistributionStrip`, no axes or labels). The
averages and highest hits are gone from the rows; the chart and the stats grid above already carry
those for the filtered set.

Everything else moved into a hover tooltip, which is now a real Swing component rather than an
HTML string: `EquipmentPanel` draws the worn gear as item icons in the game's own equipment
arrangement (head; cape, amulet, ammo; weapon, body, shield; legs; gloves, boots, ring), with
levels under their skill icon sprites and the prayers that were up. Arms, hair and jaw are left out
of the grid rather than drawn as permanently empty boxes.

Implementation notes: the row overrides `createToolTip()` and needs a non-null `setToolTipText`
to register with the tooltip manager at all. Item images are requested when the row is built so
the first hover is not a grid of empty boxes, since `ItemManager.getImage` loads asynchronously.
The panel now takes `ItemManager` and `SpriteManager`.

Tests are at 43.

## 2026-09-02 (night) - Searchable dropdowns

The separate search box under each combo was clumsy. Replaced both with `FilterSelect`, one
control per filter, built on the same shape as the GE Helper plugin's search: an `IconTextField`
with a `JPopupMenu` of suggestions under it, arrow keys and Enter to pick, and the field's clear
button to drop back to all. A small arrow to the right lists everything without typing, which is
what makes it a dropdown as well as a search. Weapon suggestions carry their item icon, and every
suggestion shows the attack count behind it, so it is obvious where the data actually is.

Monster, weapon and attack all use it. Target stays a plain combo box: three fixed choices with
nothing to search.

Two things worth remembering:

- Writing to the field fires its document listener, which would pop the suggestions straight back
  open after a pick or a clear. `setFieldText` suppresses that.
- A selection is never dropped when the option lists are replaced. It can vanish from its own list
  because of the other filters, and silently resetting it would change the numbers on screen
  without the user asking. The old combo box code did exactly that.

`repopulate`, `optionKey` and the combo `selected` helper are gone with it.

## 2026-09-02 (very late) - Gear filters, HP out of the key, and a readable chart

### Why rows kept saying "1 attack"

Reported as rows sometimes showing 2 attacks and sometimes 1. Measured against the recorded
session rather than guessed at: the file held **913 contexts**, and re-keying it without Hitpoints
and Prayer collapsed it to **101** for exactly the same hits. 837 of the 913 had a single attack.
One merged group spanned 25 different Hitpoints values.

Hitpoints and Prayer drift every few ticks in a fight and cannot change the damage dealt, so they
were pure noise in the key. Both are gone from `CombatContext.SKILL_NAMES` and from the tooltip.
File format is version 4. Defence is kept: it does not affect damage either, but it barely moves,
and it is worth showing next to Attack and Strength.

### Filtering by any worn slot

Every equipment slot moves the damage, not just the weapon, so `HistoryFilter` now carries a
slot-to-item map instead of a single `weaponId`; the weapon is simply slot 3, and `getWeaponId()`
is a convenience over the map. `withoutNpc`, `withoutAttack` and `withoutSlot` keep the cascading
option lists readable.

Eleven more dropdowns would have swamped the panel, so the filter is `EquipmentFilterPanel`: the
game's own equipment arrangement, clickable, folded away behind a toggle until asked for. A slot
with a filter shows the item and an orange border. Clicking one lists only what was actually worn
there in the hits matching the rest of the filter, with counts. `EquipmentLayout` now holds the
arrangement shared with the read-only tooltip view. The weapon search box and the weapon slot
write to the same entry and are kept in sync.

Slots nothing was ever worn in are not offered at all.

### Rows

Down to weapon and attack, the monster, and the chart. The attack count moved into the tooltip.
Worth stating plainly, since it caused the confusion above: a row is one *setup*, not one hit.

### The zero bar no longer flattens the chart

Both charts now scale to the tallest **damage** bar rather than the tallest bar overall. The miss
and splash bars are drawn cut off at the top, with their true count printed above them in the big
chart. The miss rate stays visible without costing the detail the chart exists to show, and the
accuracy and splash percentages in the stats grid carry the exact numbers either way.

Tests are at 45.

## 2026-09-02 (last) - The damage history becomes a log

### It should have been a list of hits all along

Asked twice why a row said 2 attacks and not 1, and then plainly: why bars, just show the number.
The answer is that a row was one *setup*, not one hit, and no amount of relabelling was going to
make that read as what was expected.

So the aggregates keep doing what they are good at, powering the statistics and the chart, and a
capped log of individual hits now sits beside them. `HitRecord` is timestamp, npc, weapon, damage,
a max-hit flag and the context key; 500 by default, configurable to 5000, trimmed oldest-first so
the file cannot grow without bound. Rows are now damage, monster, weapon, one hit each. The
per-row bar strips are gone with `DistributionStrip`.

The hit log is the only capped thing in the file. Per-context totals are never dropped, so
shrinking the log costs recent detail and no history.

### The style filter meant almost nothing

It said "Melee" or "Ranged", which is one bit of information the weapon already gives. It now
reports the attack style the combat tab was actually on: Accurate, Aggressive, Rapid, Longrange,
Casting, or the spell name. That was already recorded; only the label changed.

The damage type the game shows beside it, Stab / Slash / Crush, is **not** available: RuneLite
exposes exactly one attack-style parameter (`ParamID.ATTACK_STYLE_NAME`, 1407) and no damage-type
one. Deriving it needs a hardcoded weapon-category table, which is what the DPS calculators do and
what goes stale on every update. Not worth it for a filter.

### Smaller things

- The weapon dropdown is gone; the gear filter's weapon slot is the one place it lives now, which
  also removes the two-controls-one-value sync that came with it.
- Target defaults to "Not protecting".
- Tooltip levels moved beside the equipment grid instead of under it, into space that was empty.
- The chart's cryptic "top N" is gone. Two caption lines above the chart now say how many misses
  and splashes there were with their rates, and what the chart's height is worth in hits. Cut-off
  bars print their count in their own colour so it is obvious which bar it belongs to.
- `TipLabel`: the setup tooltip only appeared over the few pixels of a row not covered by its own
  labels, because a child with no tooltip does not inherit its parent's. The labels carry it now.

File format is version 5. Tests are at 49.

## 2026-09-02 (fixes) - A real attribution bug, and a chart that fits

### A bow recorded as magic

Asked how a splash could be registered for a bow. It was a genuine bug, and the file said so
plainly. Grouping every context by weapon and style:

    MAGIC  : bow, halberd, staff
    MELEE  : bow, sceptre, staff
    RANGED : staff

Weapons and styles were mismatched throughout, and the one bow splash was a context with
`style=MAGIC`, `weapon=Corrupted bow`, `attackSpeed=5` -- the spell speed, not the bow's.

Cause: the snapshot was taken inside the `AnimationChanged` handler. The worn container and the
combat-style varbits arrive as their own events in the same tick, and the animation can be
delivered before them, so the snapshot paired the newly equipped weapon with the previous weapon's
style. In the Gauntlet, where weapons are swapped every few ticks, that happened constantly.

The context is now built in `captureAttack`, at the end of the tick, once everything it reads has
settled. `AnimationChanged` only records that an animation happened and against whom. The held
animation sampling folds into the same method: if no event named a target this tick but the player
is animating at an NPC, that is the attack.

Records written before this are wrong wherever a swap raced the animation, and cannot be repaired
after the fact.

### The chart is horizontal now

Vertical bars gave each damage value a few pixels of width in a sidebar, so the labels collided
past about twenty. One horizontal bar per amount, label on the left, count at the end.

Damage bars are always drawn to scale against the longest damage bar -- no cutting off, which was
the complaint. Misses and splashes are the exception and are the only bars allowed to run past the
edge, with a torn end and their real count. They sit above a divider, apart from the damage they
are not part of. The panel's height now follows the number of rows and the sidebar scrolls.

### Smaller things

- Gear filter starts expanded.
- Arrow keys in a search box no longer scroll the sidebar underneath: the key events are consumed
  now that they drive the suggestion list.
- Tooltip levels are right-aligned in their own column, so the numbers line up whatever their
  width.

## 2026-09-02 (review) - Four bugs from a read-through, and the loose ends behind them

A cold read of the whole tree, looking for bugs first and rough edges second. Nothing was broken
on the everyday melee and ranged path; the problems were in the corners.

### A block animation ate the manually cast spell

`offerAttack` cleared `manualSpell` on every animation event before the matcher had said whether
the animation was an attack. Getting hit while facing the target is an animation event too. So:
click Cast, take a hit before the spell fires, and the block animation consumed the spell name.
The real cast that followed had none, took the combat tab's style, and a staff on Bash went into
the file as a melee attack at the staff's melee speed. Its splash could not match either, because
the attack was not flagged as magic.

The spell is now consumed only by an attack the matcher accepts. The 25-tick timeout is unchanged.

### A failed read could end with the file overwritten

`load` set the player name and the file before `read` ran, and `read` caught only `IOException`
and `JsonSyntaxException`. Gson reports an I/O failure mid-stream as `JsonIOException`, which is
neither. Had that escaped, the store would have counted as loaded with nothing in it; the next
hit would have marked it dirty and the autosave would have replaced the player's history with
that nothing. Unlikely, but the cost was everything. `read` now catches every `RuntimeException`
and moves the file aside as it already did for a syntax error, and `load` reads before it claims
to be loaded.

### Splash rate was watered down by melee

`getSplashRate` divided splashes by every hitsplat in the filter. "All styles" at a boss that had
also been meleed showed a rate far below the truth. The aggregate now counts hitsplats from magic
contexts separately and divides by those plus the splashes.

### Orphan retries stamped the wrong tick

A hit retried a tick after it arrived was matched with the current tick, so the attack's
resolved tick and the fight's end tick came out a tick late. It is matched with its own tick now.

### Another player's splash is taken back

The README said another player's splash on your target was ignored. It was, but only while you
had no spell in the air. With one unresolved, any splash graphic on that NPC was credited to it,
and when your own hitsplat then landed the attack was already resolved and the hit went
unattributed. Wrong twice.

An attack cannot both splash and hit, which is enough to fix it. A splash now leaves the attack
open (`PendingAttack.resolvedBySplash`); a later hitsplat of ours on the same NPC is the attack's
real result, and the tracker reverses the splash: `ContextStats.undoSplash`, the splash's
`HitRecord` comes off the log, and the fight's tally drops by one. Spill onto other NPCs never
triggers the reversal, since that is a different target.

The first cut of this reopened the splashed attack unconditionally, and the existing test
`splashedAttackDoesNotAbsorbNextHit` caught it at once: with the next cast already in the air, the
older splashed attack took that cast's hit. So a splashed attack is only a fallback. A hit prefers
a genuinely open attack on the same target and falls back to the splashed one only when there is
none. The one case left is both of us splashing on the same target in the same tick, which counts
as one; there is no telling those apart.

### Defence is out of the key

Rule 12 says only what can move the damage belongs in the context. Defence cannot, and an NPC
that drains it, or a Defence-only potion, was splitting one setup into several. It joins Hitpoints
and Prayer outside the key. File format is version 6.

This is the first format change that alters the *shape* of a stored record rather than adding
a field, so `upgrade` does real work: every context read from an older file has Defence dropped
from its level arrays, in place, with its key kept. Without that, the panel would have read an
old record's Ranged level as Magic. Records from before format 4 carry seven levels; the two
trailing ones fall off the end at the same time. A key-preserving private constructor on
`CombatContext` does the copy, because Gson bypasses the public one and the key is what the map
is stored under.

### Smaller things

- The snapshot was built every tick a held animation was sampled, and then thrown away by the
  matcher's cooldown check. That is a SHA-256, every worn item's composition, every prayer varbit
  and the overhead read, most ticks of every fight. `AttackMatcher.inCooldown` is asked first.
- Changing the hit window mid-fight rebuilt the matcher and dropped the attacks still waiting
  for their hits. The window is adjusted in place now.
- The spec-energy fix-up in `onVarbitChanged` has been unreachable since contexts moved to
  GameTick, which runs after every varp update in the tick. Gone, along with the setter it needed.
- `Aggregate.getShares` and `copyCounts` had no callers. Gone.
- A suggestion list left open across the two-second refresh kept showing the old counts.
  `FilterSelect.setOptions` rebuilds an open popup from the query it was built with.
- An unattributed hit now logs, in debug mode, how many ticks after the last attack on that NPC
  it landed. A weapon whose later hitsplats arrive a tick after its first would show up as a run
  of those at a steady one or two. None has been confirmed; the matcher is unchanged there and
  the README says so.
- A duplicated doc comment on `SKILL_NAMES` is one comment again.

Tests are at 58. Not yet run in a live client: the manual-cast path and the splash take-back are
the two that need it.

## Damage history tooltip: room around the level icons

The four skill rows in a row's setup tooltip were laid out at 17px tall, back to back, with a
4px gap between icon and number. The gear grid beside them is 5 cells of 32px, 168px tall, so
the column was a cramped block of icons in the top corner of a mostly empty space.

Rows are 22px now, with a 4px strut between them and a 6px gap inside, and the icon sits in a
22px-wide cell that centres the sprite rather than pinning it to a 18x16 box the sprite very
nearly filled. Vertical glue above and below the column centres it against the gear grid. The
numbers still right-align down the column, so they line up whatever their width.

## Session vs all time, and clearing either

The panel gained a **View** row above the filters: *This session* or *All time*. Everything below
it -- the filter lists, the summary, the chart, the damage history and its tooltips -- is built
from whichever body of records is selected. The `Clear` button became a two-item menu over the
same distinction.

The store now keeps a second `HistoryData` beside the one it loads and saves, and every write goes
into both. That is the whole mechanism: the session is not a time filter, because the aggregates
have no per-hit timestamps to filter on -- `ContextStats` is a histogram plus counters, and the
per-hit log is capped. Counting twice while recording is cheap, exact, and needs no file format
change, so the on-disk version stays at 6.

Session boundaries follow the character, not the connection. `unload()` runs on logout, on a world
hop and on a dropped connection, so ending the session there would end it several times an hour
for anyone hopping; instead `load()` compares the incoming name against the session's owner and
only starts a new session when they differ. That also leaves the session on screen after logout,
which is when you actually want to read it.

Two consequences worth writing down:

- Names are recorded into both sides and looked up across both. `unload()` empties the file side
  while the session stays on screen, and a name learned in an earlier session only exists in the
  file; either half alone would show "NPC #2042" in one view or the other.
- Clearing the session leaves the file alone, but clearing everything also clears the session: the
  session is a subset of what was just deleted, and leaving it standing would show hits that exist
  nowhere else. Either way the tracker is reset, so a fight that opened before the clear cannot
  close afterwards and write ticks and damage the panel no longer counts.

`HitDistributionStore` grew a package-private directory seam so the session-across-relog test can
use a temporary folder instead of writing into the player's real `~/.runelite`. Tests are at 62.

## Killing blows, and a view of the current fight

Two things the panel could not say before: what the weapon rolls as opposed to what it was
allowed to deal, and how the fight going on right now is going.

### Killing blows

The hit that kills a monster is `min(roll, remaining HP)`: a censored sample. Charting it pushes
counts out of the tail and into the low bars, and at a low-hitpoint monster every kill donates
one. Nobody else's plugin cares because nobody else charts the distribution; for this one it is
the most on-brand correction available.

`ContextStats` now keeps `killCounts` beside `counts`. The death is only seen after the hit, so a
hit is counted in `counts` first and moved by `markKillingBlow` once the tick has settled -- the
same take-back shape as `undoSplash`. The hitsplat and max-hit totals are not decremented; they
count every hit, and `killingBlows` / `killingBlowMaxHits` say how many of those ended a fight.
File format is 7. Older records read with `killCounts` null (rule 13; the getter hides it) and
keep their killing blows mixed in, because there is no telling them apart after the fact.

Detection is not by hitpoints: `getHealthRatio()` is a 30-step scale and only valid while the
bar is drawn. Instead `ActorDeath` is noted on the fight and acted on at the end of the tick, in
`closeDeadFights` after `retryOrphans`, because the killing hitsplat lands on the same tick and
nothing promises which event is delivered first. Closing at once would either miss the hit or
mark the wrong ones. Every hit recorded on the death tick is marked: with a multi-hit weapon
there is no telling which one met the cap, and marking all of them is the reading that never
lies. A hit that arrives after the death, if any weapon does that, is simply never marked. A
despawn with `isDead()` is treated as a death for the same purpose; a despawn without one still
closes the fight at once as not killed.

The judgement call was what the toggle covers. Only the shape of the distribution follows it:
the histogram, the averages per hitsplat and per landed hit, the highest hit and the max-hit
rate. Attacks, hitsplats, total damage, average per attack, accuracy, DPS and wasted ticks count
every hit either way, because they measure what happened. Accuracy in particular must include
them: whether a hit landed was decided before its damage was capped, and killing blows always
landed, so dropping them would understate it. The cost is that total damage no longer equals the
sum of the bars when they are left out, which the note beside the switch spells out.

In the panel the switch is a checkbox under the chart, next to a note that reads either
"412 in chart" in the killing-blow colour or "412 left out" in grey. It writes the same
`includeKillingBlows` setting the config panel shows, through `ConfigManager`, and refreshes at
once rather than waiting for the two-second poll. When they are charted, each bar draws its
killing blows as a darker segment at its end, the bar's tooltip counts them, and the damage
history tags the rows with "kill" in the same colour.

### Current fight

A third `HistoryScope`, narrowest first in the View row. Same mechanism as the session: a third
`HistoryData` in the store, mirrored on every write, so the store now has `all()` and every write
loops over it (rule 15).

The trigger is where the design has to be honest. Fights are tracked per NPC instance and several
can be open at once, so "the current fight" is ill-defined in multi. The window is everything
since the first attack after the last kill: at a boss that is exactly the fight, and in multi it
is "since the last thing died", which the README says plainly. The reset happens on the next
attack rather than at the kill, so the finished fight stays readable until the next one starts --
the DPS counter's pause-on-death behaviour rather than its reset-on-death one, because a panel
that goes blank at the moment of death is a panel you cannot read the kill from. The status line
says which it is showing: "Fighting for 1m 12s", "Vorkath died 34s ago", or "No fight yet".

Tests are at 68.
