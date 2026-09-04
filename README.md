# Hit Stats

A RuneLite plugin that records every hit you deal and shows the damage distribution behind it.
Filter by monster, weapon and attack type, and see average damage, accuracy, splash rate, max-hit
rate, DPS, wasted ticks and kill times for exactly the gear, stats and prayers you were using.

Everything is recorded locally. Nothing leaves your machine.

## What it shows

Open the sidebar panel (the bar-chart icon) to see:

- A **View** switch: **Current fight**, **This session** or **All time**. Every filter,
  statistic, chart and history row below it follows the switch, so each view is the same panel
  over a smaller body of records. Which one the panel opens on is a setting.
  - *Current fight* is everything since the first attack after the last kill. At a boss that is
    the fight in progress; when it dies the numbers stay until the next fight starts, so a kill
    can be read after the fact. In multi-way combat it is "since the last thing died", and the
    status line says which it is showing.
  - *This session* is everything since the character logged in. It survives a world hop or a
    dropped connection and starts over when a different character logs in or the client closes.
  - Only *All time* is written to disk.
- **Filters** for monster and style. Each is a search box that doubles as a dropdown: type to
  narrow it, or click the arrow to list everything recorded, ordered by how much you used it and
  showing the attack count behind each. Arrow keys and Enter work, and the clear button drops back
  to all. The lists narrow to each other and to the gear filter, while never removing an option
  from their own list.
- **Style** is the attack style the combat tab was set to when you attacked: Accurate, Aggressive,
  Defensive, Controlled, Rapid, Longrange, Casting, or the spell you cast. The damage type the
  game shows next to it, Stab or Slash or Crush, is not exposed by the client API, so it cannot be
  recorded without a hardcoded weapon table that would go stale on the next update.
- **Count attacks into protection** is off by default, so the numbers describe attacks the
  monster was *not* praying against. A target praying against the style you used takes far less
  damage from it, which is a different distribution and almost never the one you are asking
  about; the note beside the switch says how many attacks are being held back, and turning it on
  folds them in and adds an "Into protection" line to the summary.
- A **Gear filter**, folded away until you open it, shaped like the game's equipment tab. Click
  a slot to pick from the items actually worn there in the hits that match everything else. Every
  worn slot moves the damage, so every slot can be filtered, and the weapon is simply the weapon
  slot rather than a box of its own. Having worn *nothing* in a slot is a choice like any other:
  it sits directly under "Any", and a slot pinned to it shows "none".
- **Summary** of the matching hits:
  - attacks, hitsplats and total damage
  - average per attack (splashes and zeros included), per hitsplat, and per landed hit
  - accuracy, and for magic the splash rate
  - the share of attacks made into a target that was praying against the style used
  - highest hit and how often the game flagged a max hit
  - DPS over the ticks spent in combat
  - wasted ticks, in total, as a share of combat time, and per attack
  - kills, and average kill time
- **Distribution chart**: one horizontal bar per hitsplat amount, labelled on the left with its
  count at the end. Grey is a miss, orange a hit, gold the highest hit, purple a splash. Every
  damage bar is drawn to scale against the longest one, so their lengths compare directly. Misses
  and splashes sit above a divider and are the only bars that can run past the edge, torn off at
  the end with their true count beside them; they routinely outnumber any single damage value
  several times over, and scaling to them would squash every real bar to nothing. Two lines above
  the chart give the miss and splash rates. Hover a bar for its share.
- **Damage history**: the individual hits matching the filter, newest first. One row per hit,
  showing the damage, the monster and the weapon. Grey is a miss, purple a splash, gold a max hit.
  Hover a row to see the setup that produced it: the worn equipment as item icons arranged the way
  the game's equipment tab arranges them, the levels beside them, the prayers that were up, and
  how many attacks share that setup.

## How it works

Hits are attributed at **attack time**, not when the hitsplat appears. The snapshot is taken at
the end of the game tick rather than the instant the animation arrives: worn equipment and the
combat-style settings reach the client as separate events, and an animation can arrive before
them, which would pair the weapon you just equipped with the style of the one before it. When your character plays
an attack animation at an NPC the plugin snapshots everything that can affect the damage roll:
the fourteen equipment slots, the levels that affect damage, the prayers that affect damage,
weapon category and attack style, autocast or manually cast spell, whether a special attack fired,
the target's id, and the overhead prayer the target was showing. The hitsplat that lands a few ticks later is
matched back to that snapshot, so switching gear while a projectile is in the air still credits
the weapon that fired it.

Not every attack fires an animation event. An animation held across ticks, or re-set to the id it
already had, produces none, so the animation is also sampled once a tick. Anything inside the
previous attack's cooldown is discarded, and an attack that never produces a hitsplat or splash is
never recorded, so eating or an emote mid-fight costs nothing.

Each distinct snapshot is a *context*. Hits are counted into a per-context histogram rather than
stored one by one, so a year of PvM is a few thousand small records rather than millions of
rows. Fights are summarised per NPC instance for kill counts, kill times and DPS.

Hitpoints, Prayer and Defence levels are not recorded. The first two drift every few ticks in a
fight and cannot change the damage you deal, so keying on them turned a single setup into a stream
of one-attack rows. Dropping them collapsed one recorded session from 913 rows to 101 for exactly
the same hits. Defence cannot change the damage you deal either, and a monster that drains it was
splitting contexts for nothing.

Only prayers that change the damage you deal are recorded. Protection prayers, defence prayers
and the restore and utility ones change nothing about your own hitsplats, so flicking Protect from
Melee at a boss no longer splits one set of gear into two distributions. The exclusion list is
deliberately short: a prayer the plugin does not recognise is kept, because an extra row costs
nothing while wrongly merging two distributions produces a misleading average.

The target's overhead prayer is part of the context because it moves the distribution more than
most gear does. A monster praying against your style takes far less damage from it, so folding
both cases into one average understates what your setup actually does. Only overheads that
protect against the style you used mark an attack as blocked; Retribution, Smite, Redemption,
Wrath and Soul Split are recorded but do not.

Splashes produce no hitsplat, so they are detected from the splash graphic on the target and
matched to your own pending magic attack on that NPC. The graphic does not say whose spell it was.
When you have no spell in the air, another player's splash is ignored because there is no attack
of yours to pair it with. When you do, it is credited to your attack for the moment; if your own
hitsplat then lands on that target, the splash is taken back, because one attack cannot both
splash and hit. Only if both of you splash on the same target in the same tick is the count off,
and then only by one.

### Killing blows

The hit that kills a monster is capped by its remaining hitpoints: a roll of 30 into a monster
with 7 left shows as a 7. That hit landed and did 7 damage, but it says nothing about what the
weapon rolls, and at a low-hitpoint monster every kill contributes one. The **Count killing
blows** switch under the chart (also in the settings) charts the distribution with or without
them.

Only the shape of the distribution follows the switch: the bars, the averages per hitsplat and
per landed hit, the highest hit and the max-hit rate. Attacks, hitsplats, total damage, accuracy,
DPS and wasted ticks count every hit either way, because they measure what happened rather than
what the weapon tends to do. When killing blows are charted, each bar shows its share of them as
a darker segment at its end, and the damage history marks them with "kill".

Every hit on the tick a monster dies is treated as a killing blow. With a weapon that hits more
than once per attack there is no telling which of them met the cap, so all of them are set aside
rather than guessing. Hits recorded before this version cannot be told apart after the fact and
stay in the distribution whatever the switch says.

Wasted ticks are the gap between consecutive attacks minus the weapon's attack speed (Rapid takes
a tick off, spells cast on a fixed 5-tick cycle, 4 with a harmonised nightmare staff on the
standard spellbook). A gap longer than the configured idle gap counts as leaving combat, not as
waste.

## Data

History lives in `~/.runelite/hit-stats/<character name>.json` and is written every couple
of minutes and on logout. The `Clear` button offers two things, each asking first:

- **This session** starts the session counters over and leaves the file alone.
- **Everything** deletes the current character's file, and the session with it, since the session
  is a subset of what was just thrown away.

The file contains: NPC ids with the name and combat level seen, item ids with names, one record
per context with its histogram and counters, one record per fight, and a capped log of the most
recent individual hits. It is plain JSON so it can be opened, backed up or analysed with other
tools.

Only the hit log is capped. The statistics and the chart are built from per-context totals that
are never dropped, so shrinking the log loses recent detail but no history.

## Sharing with other players (optional)

Off by default. Nothing leaves your client until you turn on **Share my hits** in the Community
section of the settings.

With it on, the panel draws everyone else's distribution beside your own whenever the filter names
a monster and a weapon, and compares the statistics that describe the shape of a distribution
rather than how long you have played: average per hitsplat, average per landed hit, accuracy,
splash rate, max-hit rate, highest hit, DPS and wasted ticks per attack.

What is sent, for each combination of worn gear, Attack/Strength/Ranged/Magic levels, damage
prayers, attack style, spell, special attack, target monster and the monster's overhead prayer:

- the count of hits at each damage value, and the killing blows separately
- attacks, hitsplats, splashes, max hits, wasted ticks and active ticks
- the item and monster names for the ids involved, so other people's panels can label them
- a random id identifying your history file, so your own numbers can be updated rather than added
  to twice

What is never sent: your character name, your account, the world, where you are, anything you
typed, and anything about any other player. The random id is generated locally and is not derived
from anything about your account. Clearing your history creates a new one.

Your statistics are sent when you log out, when you close the client, once after you log in, and
on a timer while you play. Never per attack. The panel's bottom line says when the last upload
happened and shows the first characters of your id; quote it in a GitHub issue if you ever want
your data removed.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| Split monsters by id | off | One entry per NPC id rather than per name, to separate boss phases that share a name |
| Show damage history | on | List the individual hits behind the chart |
| History rows | 30 | Most hits to list under the chart |
| Hits kept | 500 | How many individual hits the file holds |
| Hit window | 6 ticks | How long after an attack animation its hit may still arrive |
| Idle gap | 10 ticks | A pause longer than this is a break, not wasted ticks |
| Autosave interval | 2 min | How often the file is written while logged in |
| Debug logging | off | Log each attack, hit and splash decision |
| Share my hits | **off** | Send your statistics and see everyone else's; see above |
| Share every | 30 min | How often statistics are sent while you are logged in |
| Show the community chart | on | Draw everyone else's distribution beside yours |
| Match levels | Same level bracket | Which other players to compare against: any level, the same five-level bracket of your style's main skill, or exactly your levels |

## Limitations

- Only damage to NPCs is tracked. Player targets are ignored.
- An attack is counted once it produces a hitsplat or a splash. One that produces neither, because
  the monster died to someone else first or you walked out of range, is not counted, so accuracy
  stays honest.
- Only your own hitsplats count. Thralls, poison, venom, and other players are excluded. Damage
  that has no attack of yours to pair with (vengeance, recoil, a missed animation) is counted as
  "unattributed" in the status line and kept out of the distribution.
- Boosted levels are part of the context, so a decaying potion produces a new context each time a
  level drops. The filters merge them; the breakdown shows them separately.
- Special attacks that hit faster than the weapon's listed speed may have their wasted ticks
  slightly overstated.
- An attack accepts extra hitsplats only on the tick its first one landed. A weapon whose later
  hitsplats arrive a tick after the first would have those counted as unattributed rather than
  charted. No such weapon has been confirmed yet; with debug logging on, an unattributed hit
  reports how many ticks after the last attack on that monster it landed, which is what would
  show it.
- The target's overhead is read when you attack. A monster that switches prayers while your
  projectile is in the air is recorded as it was when you fired.
- Overhead icons from an archive the plugin does not recognise (some custom boss icons) are
  recorded as no overhead rather than guessed at.

## Building

```
.\gradlew.bat jar
.\gradlew.bat installSideload
```

`installSideload` copies the jar into `~/.runelite/sideloaded-plugins`, which the client only
loads when started with `--developer-mode`. `.\gradlew.bat test` runs the unit tests for the
matching and aggregation logic.
