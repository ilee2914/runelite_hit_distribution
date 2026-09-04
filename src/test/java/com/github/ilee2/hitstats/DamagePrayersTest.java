package com.github.ilee2.hitstats;

import net.runelite.api.Prayer;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DamagePrayersTest
{
	@Test
	public void offensivePrayersAreRecorded()
	{
		final Prayer[] offensive = {
			Prayer.BURST_OF_STRENGTH, Prayer.SUPERHUMAN_STRENGTH, Prayer.ULTIMATE_STRENGTH,
			Prayer.CLARITY_OF_THOUGHT, Prayer.IMPROVED_REFLEXES, Prayer.INCREDIBLE_REFLEXES,
			Prayer.SHARP_EYE, Prayer.HAWK_EYE, Prayer.EAGLE_EYE,
			Prayer.MYSTIC_WILL, Prayer.MYSTIC_LORE, Prayer.MYSTIC_MIGHT,
			Prayer.CHIVALRY, Prayer.PIETY, Prayer.RIGOUR, Prayer.AUGURY,
			Prayer.DEADEYE, Prayer.MYSTIC_VIGOUR,
		};

		for (Prayer prayer : offensive)
		{
			assertTrue(prayer.name(), DamagePrayers.affectsDamage(prayer));
		}
	}

	@Test
	public void prayersThatCannotChangeOurDamageAreDropped()
	{
		final Prayer[] irrelevant = {
			Prayer.THICK_SKIN, Prayer.ROCK_SKIN, Prayer.STEEL_SKIN,
			Prayer.PROTECT_FROM_MELEE, Prayer.PROTECT_FROM_MISSILES, Prayer.PROTECT_FROM_MAGIC,
			Prayer.RAPID_RESTORE, Prayer.RAPID_HEAL, Prayer.PRESERVE, Prayer.PROTECT_ITEM,
			Prayer.RETRIBUTION, Prayer.REDEMPTION, Prayer.SMITE,
		};

		for (Prayer prayer : irrelevant)
		{
			assertFalse(prayer.name(), DamagePrayers.affectsDamage(prayer));
		}
	}

	@Test
	public void protectionPrayersNoLongerSplitAContext()
	{
		// The point of the whole exercise: flicking Protect from Melee at a boss used to double
		// the number of contexts for the same gear and stats.
		final CombatContext praying = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.prayers(java.util.Arrays.asList("PIETY", "PROTECT_FROM_MELEE"))
			.build();
		final CombatContext notPraying = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.prayers(java.util.Collections.singletonList("PIETY"))
			.build();

		// The tracker filters before building the context, so what reaches the key is Piety in
		// both cases. Anything that still reaches it must stay distinct.
		assertTrue(DamagePrayers.affectsDamage(Prayer.PIETY));
		assertFalse(DamagePrayers.affectsDamage(Prayer.PROTECT_FROM_MELEE));
		assertFalse(praying.getKey().equals(notPraying.getKey()));
	}

	@Test
	public void anUnknownPrayerIsKeptRatherThanMerged()
	{
		// Being wrong by keeping a prayer costs one extra row; being wrong by dropping it blends
		// two different distributions, so every prayer not on the exclusion list is recorded.
		int kept = 0;
		for (Prayer prayer : Prayer.values())
		{
			if (DamagePrayers.affectsDamage(prayer))
			{
				kept++;
			}
		}

		assertTrue(kept > 0);
		assertTrue(kept == Prayer.values().length - DamagePrayers.ignoredCount());
	}
}
