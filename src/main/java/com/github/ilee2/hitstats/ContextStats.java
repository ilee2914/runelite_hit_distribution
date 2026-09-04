package com.github.ilee2.hitstats;

import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Everything recorded for one {@link CombatContext}: a histogram of hitsplat amounts plus the
 * counters the summary statistics derive from. Index 0 of the histogram is a miss (a 0 hitsplat);
 * splashes produce no hitsplat and are counted separately.
 *
 * <p>Killing blows live in a histogram of their own. The hit that kills a monster is capped by
 * its remaining hitpoints, so it is not a fair sample of what the weapon rolls; the panel can
 * chart the distribution with or without them. The death is only seen after the hit, so a hit is
 * counted in {@link #counts} first and moved by {@link #markKillingBlow} a moment later.
 */
@Getter
public class ContextStats
{
	private static final int[] NO_COUNTS = new int[0];

	private CombatContext context;

	/** {@code counts[d]} is how many hitsplats of {@code d} damage were seen, killing blows aside. */
	private int[] counts = new int[1];

	/**
	 * {@code killCounts[d]} is how many hitsplats of {@code d} damage ended a fight. Null in
	 * files from before format 7, whose killing blows are still in {@link #counts} and cannot be
	 * told apart after the fact.
	 */
	@Nullable
	private int[] killCounts;

	/** Attacks committed in this context (an attack may produce several hitsplats). */
	private int attacks;

	/** Hitsplats recorded, including zeros and killing blows. */
	private int hitsplats;

	private int splashes;

	/** Hitsplats the game flagged as a max hit, killing blows included. */
	private int maxHits;

	/** How many hitsplats are in {@link #killCounts}. */
	private int killingBlows;

	/** How many of the killing blows the game flagged as a max hit. */
	private int killingBlowMaxHits;

	/** Ticks the player could have attacked on but did not. */
	private int wastedTicks;

	/** Ticks spent in combat, for DPS. */
	private int activeTicks;

	private long firstSeen;
	private long lastSeen;

	ContextStats()
	{
		// For Gson.
	}

	ContextStats(CombatContext context)
	{
		this.context = context;
		this.firstSeen = System.currentTimeMillis();
		this.lastSeen = firstSeen;
	}

	/** Deep copy, so the Swing thread can read a snapshot while the client thread keeps writing. */
	ContextStats copy()
	{
		final ContextStats c = new ContextStats();
		c.context = context;
		c.counts = Arrays.copyOf(counts, counts.length);
		c.killCounts = killCounts == null ? null : Arrays.copyOf(killCounts, killCounts.length);
		c.attacks = attacks;
		c.hitsplats = hitsplats;
		c.splashes = splashes;
		c.maxHits = maxHits;
		c.killingBlows = killingBlows;
		c.killingBlowMaxHits = killingBlowMaxHits;
		c.wastedTicks = wastedTicks;
		c.activeTicks = activeTicks;
		c.firstSeen = firstSeen;
		c.lastSeen = lastSeen;
		return c;
	}

	/** Brings a record read from an older file up to the current context shape. Keeps the key. */
	void migrate()
	{
		if (context != null)
		{
			context = context.withCurrentLevelShape();
		}
	}

	/** Replaces the context, and with it the key this record belongs under. See format 8. */
	void rekey(CombatContext replacement)
	{
		context = replacement;
	}

	/**
	 * Folds {@code other} into this record. Every counter here describes the same setup under the
	 * same key, so all of them are additive: histograms sum index by index, totals sum, and the
	 * window widens to cover both. Used by the format 8 migration, where several records written
	 * under stale keys turn out to describe one setup.
	 */
	void mergeFrom(ContextStats other)
	{
		counts = sum(counts, other.counts);
		killCounts = sum(killCounts, other.killCounts);
		attacks += other.attacks;
		hitsplats += other.hitsplats;
		splashes += other.splashes;
		maxHits += other.maxHits;
		killingBlows += other.killingBlows;
		killingBlowMaxHits += other.killingBlowMaxHits;
		wastedTicks += other.wastedTicks;
		activeTicks += other.activeTicks;
		firstSeen = earliest(firstSeen, other.firstSeen);
		lastSeen = Math.max(lastSeen, other.lastSeen);
	}

	/**
	 * @return {@code a} and {@code b} added index by index, at the length of the longer. Null is
	 * an empty histogram, and stays null when both sides are empty so a record that never had a
	 * killing blow does not gain an array by being merged.
	 */
	@Nullable
	private static int[] sum(@Nullable int[] a, @Nullable int[] b)
	{
		if (a == null || a.length == 0)
		{
			return b == null ? a : Arrays.copyOf(b, b.length);
		}
		if (b == null || b.length == 0)
		{
			return a;
		}

		final int[] out = Arrays.copyOf(a, Math.max(a.length, b.length));
		for (int i = 0; i < b.length; i++)
		{
			out[i] += b[i];
		}
		return out;
	}

	/** Zero means "never recorded", not "1970", so it must not win a minimum. */
	private static long earliest(long a, long b)
	{
		if (a <= 0)
		{
			return b;
		}
		if (b <= 0)
		{
			return a;
		}
		return Math.min(a, b);
	}

	/** Never null, whatever the file said. */
	public int[] getKillCounts()
	{
		return killCounts == null ? NO_COUNTS : killCounts;
	}

	void recordAttack(int wasted, int active)
	{
		attacks++;
		wastedTicks += wasted;
		activeTicks += active;
		touch();
	}

	void recordHit(int amount, boolean max)
	{
		if (amount < 0)
		{
			amount = 0;
		}
		if (amount >= counts.length)
		{
			counts = Arrays.copyOf(counts, amount + 1);
		}
		counts[amount]++;
		hitsplats++;
		if (max)
		{
			maxHits++;
		}
		touch();
	}

	/**
	 * Moves one hitsplat of {@code amount} out of the histogram and into the killing blows. The
	 * hitsplat and max-hit totals are untouched: they count every hit, and the killing-blow
	 * counters say how many of them ended a fight.
	 *
	 * @return false if the histogram holds no such hitsplat, in which case nothing changes.
	 */
	boolean markKillingBlow(int amount, boolean max)
	{
		if (amount <= 0 || amount >= counts.length || counts[amount] == 0)
		{
			return false;
		}

		counts[amount]--;
		if (killCounts == null)
		{
			killCounts = new int[amount + 1];
		}
		else if (amount >= killCounts.length)
		{
			killCounts = Arrays.copyOf(killCounts, amount + 1);
		}
		killCounts[amount]++;
		killingBlows++;
		if (max)
		{
			killingBlowMaxHits++;
		}
		touch();
		return true;
	}

	void recordSplash()
	{
		splashes++;
		touch();
	}

	/** Takes back a splash that turned out to be someone else's; see the tracker. */
	void undoSplash()
	{
		if (splashes > 0)
		{
			splashes--;
			touch();
		}
	}

	private void touch()
	{
		lastSeen = System.currentTimeMillis();
	}

	/** Every point of damage dealt in this context, killing blows included. */
	public int getTotalDamage()
	{
		return damageIn(counts) + getKillingBlowDamage();
	}

	public int getKillingBlowDamage()
	{
		return damageIn(getKillCounts());
	}

	private static int damageIn(int[] histogram)
	{
		int total = 0;
		for (int d = 1; d < histogram.length; d++)
		{
			total += d * histogram[d];
		}
		return total;
	}

	public int getZeroHits()
	{
		return counts.length > 0 ? counts[0] : 0;
	}

	/** The highest hit in the distribution proper; a capped killing blow cannot beat it. */
	public int getHighestHit()
	{
		for (int d = counts.length - 1; d > 0; d--)
		{
			if (counts[d] > 0)
			{
				return d;
			}
		}
		return 0;
	}
}
