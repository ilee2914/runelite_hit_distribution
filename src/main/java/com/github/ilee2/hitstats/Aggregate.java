package com.github.ilee2.hitstats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;

/**
 * The contexts and fights matching a {@link HistoryFilter}, folded into one histogram and one
 * set of counters. Built on the client thread, read on the Swing thread; every array and list
 * in it is a copy.
 *
 * <p>Killing blows can be charted or left out. Only the statistics that describe the shape of
 * the distribution follow that choice: the histogram, the averages per hitsplat, the highest hit
 * and the max-hit rate. Attacks, hitsplats, total damage, accuracy, DPS and wasted ticks measure
 * what happened, and always count every hit.
 */
@Getter
public class Aggregate
{
	private static final double SECONDS_PER_TICK = 0.6;

	/** The histogram as charted: every hitsplat, or every hitsplat but the killing blows. */
	private final int[] counts;

	/** Killing blows by damage, whether or not they are also folded into {@link #counts}. */
	private final int[] killCounts;

	private final boolean killingBlowsIncluded;
	private final int killingBlows;
	private final int killingBlowDamage;

	private final int attacks;

	/** Every hitsplat, killing blows included. */
	private final int hitsplats;

	private final int splashes;

	/** Max hits among the charted hitsplats. */
	private final int maxHits;

	private final int wastedTicks;
	private final int activeTicks;
	private final int fights;
	private final int kills;
	private final long killTicks;
	private final int magicAttacks;

	/** Hitsplats from magic contexts, which together with the splashes are the magic attempts. */
	private final int magicHitsplats;

	private final int protectedAttacks;
	private final List<ContextStats> contexts;

	Aggregate(List<ContextStats> matched, List<KillRecord> matchedFights)
	{
		this(matched, matchedFights, true);
	}

	Aggregate(List<ContextStats> matched, List<KillRecord> matchedFights, boolean includeKillingBlows)
	{
		int width = 1;
		for (ContextStats c : matched)
		{
			width = Math.max(width, Math.max(c.getCounts().length, c.getKillCounts().length));
		}

		final int[] histogram = new int[width];
		final int[] kills = new int[width];
		int attackTotal = 0;
		int hitsplatTotal = 0;
		int splashTotal = 0;
		int maxHitTotal = 0;
		int killingBlowTotal = 0;
		int killingBlowMaxHits = 0;
		int wasted = 0;
		int active = 0;
		int magic = 0;
		int magicSplats = 0;
		int protectedAttacks = 0;

		final List<ContextStats> copies = new ArrayList<>(matched.size());
		for (ContextStats c : matched)
		{
			final ContextStats copy = c.copy();
			copies.add(copy);

			final int[] counts = copy.getCounts();
			for (int d = 0; d < counts.length; d++)
			{
				histogram[d] += counts[d];
			}
			final int[] killCounts = copy.getKillCounts();
			for (int d = 0; d < killCounts.length; d++)
			{
				kills[d] += killCounts[d];
			}
			attackTotal += copy.getAttacks();
			hitsplatTotal += copy.getHitsplats();
			splashTotal += copy.getSplashes();
			maxHitTotal += copy.getMaxHits();
			killingBlowTotal += copy.getKillingBlows();
			killingBlowMaxHits += copy.getKillingBlowMaxHits();
			wasted += copy.getWastedTicks();
			active += copy.getActiveTicks();
			final CombatContext context = copy.getContext();
			if (context != null)
			{
				if (context.getCombatStyle() == CombatStyle.MAGIC)
				{
					magic += copy.getAttacks();
					magicSplats += copy.getHitsplats();
				}
				if (context.isStyleProtected())
				{
					protectedAttacks += copy.getAttacks();
				}
			}
		}
		copies.sort(Comparator.comparingInt(ContextStats::getAttacks).reversed());

		int killingBlowDamageTotal = 0;
		for (int d = 1; d < width; d++)
		{
			killingBlowDamageTotal += d * kills[d];
			if (includeKillingBlows)
			{
				histogram[d] += kills[d];
			}
		}

		int fightCount = 0;
		int killCount = 0;
		long killTickTotal = 0;
		for (KillRecord f : matchedFights)
		{
			fightCount++;
			if (f.isKilled())
			{
				killCount++;
				killTickTotal += f.getDurationTicks();
			}
		}

		this.counts = histogram;
		this.killCounts = kills;
		this.killingBlowsIncluded = includeKillingBlows;
		this.killingBlows = killingBlowTotal;
		this.killingBlowDamage = killingBlowDamageTotal;
		this.attacks = attackTotal;
		this.hitsplats = hitsplatTotal;
		this.splashes = splashTotal;
		this.maxHits = includeKillingBlows ? maxHitTotal : maxHitTotal - killingBlowMaxHits;
		this.wastedTicks = wasted;
		this.activeTicks = active;
		this.fights = fightCount;
		this.kills = killCount;
		this.killTicks = killTickTotal;
		this.magicAttacks = magic;
		this.magicHitsplats = magicSplats;
		this.protectedAttacks = protectedAttacks;
		this.contexts = Collections.unmodifiableList(copies);
	}

	static Aggregate empty()
	{
		return new Aggregate(Collections.emptyList(), Collections.emptyList(), true);
	}

	public boolean isEmpty()
	{
		return attacks == 0 && hitsplats == 0 && splashes == 0;
	}

	/** Killing blows the chart is not showing: zero when they are included. */
	public int getExcludedKillingBlows()
	{
		return killingBlowsIncluded ? 0 : killingBlows;
	}

	/** Every point of damage dealt, whether or not the killing blows are charted. */
	public int getTotalDamage()
	{
		return getChartedDamage() + (killingBlowsIncluded ? 0 : killingBlowDamage);
	}

	/** Damage summed over the charted histogram. */
	public int getChartedDamage()
	{
		int total = 0;
		for (int d = 1; d < counts.length; d++)
		{
			total += d * counts[d];
		}
		return total;
	}

	/** Hitsplats in the charted histogram, zeros included. */
	public int getChartedHitsplats()
	{
		return hitsplats - getExcludedKillingBlows();
	}

	/** Hitsplats of zero damage: the blue "0" for a melee or ranged miss, or a magic hit that rolled 0. */
	public int getZeroHits()
	{
		return counts.length > 0 ? counts[0] : 0;
	}

	/** Hitsplats that did damage, killing blows included: a killing blow always landed. */
	public int getLandedHits()
	{
		return hitsplats - getZeroHits();
	}

	/** Every attempt that could have done damage: hitsplats plus splashes. */
	public int getAttempts()
	{
		return hitsplats + splashes;
	}

	/** The tallest bar among the damage values, which is what the chart is scaled to. */
	public int getDamagePeak()
	{
		int peak = 0;
		for (int d = 1; d < counts.length; d++)
		{
			peak = Math.max(peak, counts[d]);
		}
		return peak;
	}

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

	/** Mean damage per attack, splashes, zeros and killing blows all included. */
	public double getAveragePerAttack()
	{
		return attacks == 0 ? 0 : (double) getTotalDamage() / attacks;
	}

	/** Mean damage per charted hitsplat, zeros included, splashes excluded. */
	public double getAveragePerHitsplat()
	{
		final int charted = getChartedHitsplats();
		return charted == 0 ? 0 : (double) getChartedDamage() / charted;
	}

	/** Mean damage of the charted hits that did damage. */
	public double getAveragePerLandedHit()
	{
		final int landed = getChartedHitsplats() - getZeroHits();
		return landed == 0 ? 0 : (double) getChartedDamage() / landed;
	}

	/**
	 * Share of attempts that did damage. Killing blows count: whether a hit landed was decided
	 * before its damage was capped, so leaving them out would understate accuracy.
	 */
	public double getAccuracy()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) getLandedHits() / attempts;
	}

	/**
	 * Share of magic attempts that splashed: splashes over magic hitsplats plus splashes. Melee
	 * and ranged hitsplats in the same filter are left out, so an "All styles" view at a boss you
	 * also meleed does not water the rate down.
	 */
	public double getSplashRate()
	{
		final int attempts = magicHitsplats + splashes;
		return attempts == 0 ? 0 : (double) splashes / attempts;
	}

	/** Share of attacks made into a target that was praying against the style used. */
	public double getProtectedShare()
	{
		return attacks == 0 ? 0 : (double) protectedAttacks / attacks;
	}

	public double getMaxHitRate()
	{
		final int charted = getChartedHitsplats();
		return charted == 0 ? 0 : (double) maxHits / charted;
	}

	public double getDps()
	{
		return activeTicks == 0 ? 0 : getTotalDamage() / (activeTicks * SECONDS_PER_TICK);
	}

	public double getWastedShare()
	{
		return activeTicks == 0 ? 0 : (double) wastedTicks / activeTicks;
	}

	public double getWastedPerAttack()
	{
		return attacks == 0 ? 0 : (double) wastedTicks / attacks;
	}

	public double getAverageKillSeconds()
	{
		return kills == 0 ? 0 : killTicks * SECONDS_PER_TICK / kills;
	}
}
