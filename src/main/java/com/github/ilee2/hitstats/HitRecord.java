package com.github.ilee2.hitstats;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One hitsplat, kept so the panel can list what actually happened rather than only the shape of
 * it. The aggregates are what the statistics are built from; this is the log beside them.
 *
 * <p>Only the most recent few hundred are kept, so the file stays small however long the history
 * runs. {@link #contextKey} points back at the full setup, which is stored once per setup rather
 * than once per hit.
 */
@Getter
@NoArgsConstructor
public class HitRecord
{
	/** Damage value standing for a splash, which produces no hitsplat at all. */
	public static final int SPLASH = -1;

	private long timestamp;
	private int npcId;
	private int weaponId;

	/** Damage dealt, 0 for a miss, or {@link #SPLASH}. */
	private int damage;

	/** Whether the game flagged this as a max hit. */
	private boolean max;

	private String contextKey;

	/**
	 * Whether this hit ended the fight. The death is only seen after the hit, so a record is
	 * written without this and flagged a moment later. Files from before format 7 never carry
	 * it, and read as false.
	 */
	private boolean killingBlow;

	public HitRecord(long timestamp, int npcId, int weaponId, int damage, boolean max, String contextKey)
	{
		this.timestamp = timestamp;
		this.npcId = npcId;
		this.weaponId = weaponId;
		this.damage = damage;
		this.max = max;
		this.contextKey = contextKey;
	}

	public boolean isSplash()
	{
		return damage == SPLASH;
	}

	void markKillingBlow()
	{
		killingBlow = true;
	}

	/** Points this hit at the key its context now lives under; see {@code KillRecord.remapContext}. */
	void remapContext(Map<String, String> newKeys)
	{
		final String moved = newKeys.get(contextKey);
		if (moved != null)
		{
			contextKey = moved;
		}
	}
}
