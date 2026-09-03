package com.github.ilee2.hitdistribution;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One fight against one NPC instance, from the first attack to its death or despawn. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class KillRecord
{
	private long timestamp;
	private int npcId;

	/** Context of the first attack in the fight. */
	private String contextKey;

	private int weaponId;
	private int durationTicks;
	private int attacks;
	private int hitsplats;
	private int damage;
	private int misses;
	private int splashes;
	private int wastedTicks;

	/** False when the NPC despawned or the fight timed out without a death. */
	private boolean killed;
}
