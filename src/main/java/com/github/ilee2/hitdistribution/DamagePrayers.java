package com.github.ilee2.hitdistribution;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.Prayer;

/**
 * Which prayers belong in a {@link CombatContext}.
 *
 * <p>Only prayers that change the damage you deal matter here. Protection prayers, defence
 * prayers and the restore/utility ones change nothing about your own hitsplats, and flipping them
 * mid-fight would otherwise split one distribution into several for no reason.
 *
 * <p>The list below is what is <em>excluded</em>, not what is included, so a prayer this plugin
 * has never heard of is kept rather than silently merged into someone else's distribution. Being
 * wrong in that direction only costs an extra row in the breakdown; being wrong the other way
 * blends two genuinely different distributions into one misleading average.
 */
final class DamagePrayers
{
	private static final Set<Prayer> NO_EFFECT_ON_DAMAGE_DEALT = EnumSet.of(
		// Defence only.
		Prayer.THICK_SKIN,
		Prayer.ROCK_SKIN,
		Prayer.STEEL_SKIN,

		// Overhead protection: changes what you take, not what you deal.
		Prayer.PROTECT_FROM_MAGIC,
		Prayer.PROTECT_FROM_MISSILES,
		Prayer.PROTECT_FROM_MELEE,

		// Restore and utility.
		Prayer.RAPID_RESTORE,
		Prayer.RAPID_HEAL,
		Prayer.PRESERVE,
		Prayer.PROTECT_ITEM,

		// Effects that fire on damage taken or on death, not on the attacks you make.
		Prayer.RETRIBUTION,
		Prayer.REDEMPTION,
		Prayer.SMITE,

		// Ruinous equivalents of the above.
		Prayer.RP_REJUVENATION,
		Prayer.RP_PROTECT_ITEM,
		Prayer.RP_RUINOUS_GRACE,
		Prayer.RP_DAMPEN_MAGIC,
		Prayer.RP_DAMPEN_RANGED,
		Prayer.RP_DAMPEN_MELEE);

	private DamagePrayers()
	{
	}

	/**
	 * @return whether this prayer can move the damage you deal, through accuracy or through
	 * damage itself. Accuracy counts: it changes how often a zero is rolled, which is the shape
	 * of the distribution.
	 */
	static boolean affectsDamage(Prayer prayer)
	{
		return !NO_EFFECT_ON_DAMAGE_DEALT.contains(prayer);
	}

	/** @return how many prayers are deliberately left out of the context. */
	static int ignoredCount()
	{
		return NO_EFFECT_ON_DAMAGE_DEALT.size();
	}
}
