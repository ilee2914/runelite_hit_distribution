package com.github.ilee2.hitdistribution.sync;

import javax.annotation.Nullable;
import lombok.Getter;

/**
 * What everyone else's hits look like for one filter, as the server returned it.
 *
 * <p>The line is everyone who has shared data for this setup, the reader included when they have
 * shared any: subtracting the reader's own rows from a total that was summed before their last
 * upload can go negative, and the count of other players says all the panel needs to say.
 *
 * <p>Fields are the wire format, so Gson fills them directly. Everything derived is computed the
 * same way {@code Aggregate} computes it locally, or the two halves of the comparison would not
 * mean the same thing.
 */
@Getter
public class CommunityAggregate
{
	private static final double SECONDS_PER_TICK = 0.6;
	private static final int[] NO_COUNTS = new int[0];

	private boolean ok;

	/** Everyone in the sample, the reader included. */
	private int players;

	/** Everyone but the reader. What the panel shows. */
	private int others;

	private boolean includesYou;

	/** How many recorded setups were folded in; a rough measure of how broad the filter was. */
	private int buckets;

	/** Nobody has shared anything matching this filter yet. */
	private boolean empty;

	/** The filter matched more than the server will aggregate in one go. */
	private boolean tooBroad;

	@Nullable
	private int[] counts;

	@Nullable
	private int[] killCounts;

	private int attacks;
	private int hitsplats;
	private int splashes;
	private int maxHits;
	private int killingBlows;
	private int wastedTicks;
	private int activeTicks;

	@Nullable
	private Epoch epoch;

	private long rebuiltAt;

	public int[] getCounts()
	{
		return counts == null ? NO_COUNTS : counts;
	}

	public int[] getKillCounts()
	{
		return killCounts == null ? NO_COUNTS : killCounts;
	}

	/** Whether there is a distribution worth drawing. */
	public boolean hasData()
	{
		return ok && !empty && !tooBroad && hitsplats > 0;
	}

	public int getTotalDamage()
	{
		return damageIn(getCounts()) + damageIn(getKillCounts());
	}

	public int getZeroHits()
	{
		final int[] c = getCounts();
		return c.length > 0 ? c[0] : 0;
	}

	public int getLandedHits()
	{
		return hitsplats - getZeroHits();
	}

	/** Hitsplats plus splashes: everything that was an attempt to land a hit. */
	public int getAttempts()
	{
		return hitsplats + splashes;
	}

	public double getAveragePerHitsplat()
	{
		return hitsplats == 0 ? 0 : (double) getTotalDamage() / hitsplats;
	}

	public double getAveragePerLandedHit()
	{
		final int landed = getLandedHits();
		return landed == 0 ? 0 : (double) getTotalDamage() / landed;
	}

	public double getAccuracy()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) getLandedHits() / attempts;
	}

	public double getSplashRate()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) splashes / attempts;
	}

	public double getMaxHitRate()
	{
		return hitsplats == 0 ? 0 : (double) maxHits / hitsplats;
	}

	public double getDps()
	{
		return activeTicks == 0 ? 0 : getTotalDamage() / (activeTicks * SECONDS_PER_TICK);
	}

	public double getWastedPerAttack()
	{
		return attacks == 0 ? 0 : (double) wastedTicks / attacks;
	}

	public int getHighestHit()
	{
		final int[] c = getCounts();
		for (int d = c.length - 1; d > 0; d--)
		{
			if (c[d] > 0)
			{
				return d;
			}
		}
		return 0;
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

	/** The stretch of time the server is reporting on, when the owner has declared one. */
	@Getter
	public static class Epoch
	{
		private int id;
		private int startDay;

		@Nullable
		private String note;

		private boolean ready;

		/** @return the start day as {@code yyyy-mm-dd}, or null when there is no epoch. */
		@Nullable
		public String getStartLabel()
		{
			if (startDay < 10000101)
			{
				return null;
			}
			return String.format("%04d-%02d-%02d", startDay / 10000, (startDay / 100) % 100, startDay % 100);
		}
	}
}
