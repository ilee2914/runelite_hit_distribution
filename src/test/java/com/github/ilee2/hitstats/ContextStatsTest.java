package com.github.ilee2.hitstats;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextStatsTest
{
	@Test
	public void histogramGrowsToFitAndCountsRepeats()
	{
		final ContextStats s = new ContextStats(AttackMatcherTest.context(CombatStyle.MELEE, 4, 1));
		s.recordHit(10, false);
		s.recordHit(10, true);
		s.recordHit(0, false);
		s.recordHit(3, false);

		assertArrayEquals(new int[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 2}, s.getCounts());
		assertEquals(4, s.getHitsplats());
		assertEquals(23, s.getTotalDamage());
		assertEquals(10, s.getHighestHit());
		assertEquals(1, s.getZeroHits());
		assertEquals(1, s.getMaxHits());
	}

	@Test
	public void attackAndSplashCountersAccumulate()
	{
		final ContextStats s = new ContextStats(AttackMatcherTest.context(CombatStyle.MAGIC, 5, 1));
		s.recordAttack(0, 5);
		s.recordAttack(2, 7);
		s.recordSplash();

		assertEquals(2, s.getAttacks());
		assertEquals(2, s.getWastedTicks());
		assertEquals(12, s.getActiveTicks());
		assertEquals(1, s.getSplashes());
	}

	@Test
	public void aKillingBlowMovesOutOfTheHistogramButStaysInTheTotals()
	{
		final ContextStats s = new ContextStats(AttackMatcherTest.context(CombatStyle.MELEE, 4, 1));
		s.recordHit(10, false);
		s.recordHit(30, true);

		assertTrue(s.markKillingBlow(30, true));

		assertEquals(0, s.getCounts()[30]);
		assertEquals(1, s.getKillCounts()[30]);
		assertEquals(1, s.getKillingBlows());
		assertEquals(1, s.getKillingBlowMaxHits());

		// What happened is unchanged: two hitsplats, one of them a max, forty damage.
		assertEquals(2, s.getHitsplats());
		assertEquals(1, s.getMaxHits());
		assertEquals(40, s.getTotalDamage());
		assertEquals(30, s.getKillingBlowDamage());

		// The distribution proper no longer has the capped hit in it.
		assertEquals(10, s.getHighestHit());

		// Nothing left to move, and a zero can never have been a killing blow.
		assertFalse(s.markKillingBlow(30, false));
		assertFalse(s.markKillingBlow(0, false));
		assertEquals(1, s.getKillingBlows());
	}

	@Test
	public void recordsFromBeforeFormatSevenHaveNoKillingBlowsAndStillTakeThem()
	{
		final ContextStats s = new Gson().fromJson(
			"{\"counts\":[0,0,0,0,0,2],\"hitsplats\":2}", ContextStats.class);

		assertEquals(0, s.getKillCounts().length);
		assertEquals(10, s.getTotalDamage());

		assertTrue(s.markKillingBlow(5, false));
		assertEquals(1, s.getKillCounts()[5]);
		assertEquals(1, s.getCounts()[5]);
		assertEquals(10, s.getTotalDamage());
	}

	@Test
	public void copyCarriesTheKillingBlows()
	{
		final ContextStats s = new ContextStats(AttackMatcherTest.context(CombatStyle.MELEE, 4, 1));
		s.recordHit(5, false);
		s.markKillingBlow(5, false);

		final ContextStats copy = s.copy();
		s.recordHit(5, false);
		s.markKillingBlow(5, false);

		assertEquals(1, copy.getKillingBlows());
		assertEquals(1, copy.getKillCounts()[5]);
		assertEquals(2, s.getKillingBlows());
	}

	@Test
	public void copyIsIndependent()
	{
		final ContextStats s = new ContextStats(AttackMatcherTest.context(CombatStyle.MELEE, 4, 1));
		s.recordHit(5, false);
		final ContextStats copy = s.copy();
		s.recordHit(7, false);

		assertEquals(1, copy.getHitsplats());
		assertEquals(2, s.getHitsplats());
	}

	@Test
	public void contextKeyIsStableAndSensitiveToInputs()
	{
		final CombatContext a = AttackMatcherTest.context(CombatStyle.MELEE, 4, 100);
		final CombatContext b = AttackMatcherTest.context(CombatStyle.MELEE, 4, 100);
		final CombatContext other = AttackMatcherTest.context(CombatStyle.MELEE, 4, 101);

		assertEquals(a.getKey(), b.getKey());
		assertNotEquals(a.getKey(), other.getKey());
		assertNotEquals(a.getKey(), a.asSpecial().getKey());
		assertEquals(32, a.getKey().length());
	}

	@Test
	public void targetOverheadsArePartOfTheKey()
	{
		final CombatContext open = AttackMatcherTest.context(CombatStyle.MELEE, 4, 100);
		final CombatContext prayed = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Collections.singletonList("PROTECT_FROM_MELEE"))
			.styleProtected(true)
			.build();
		final CombatContext irrelevantOverhead = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Collections.singletonList("RETRIBUTION"))
			.styleProtected(false)
			.build();

		assertNotEquals(open.getKey(), prayed.getKey());
		assertNotEquals(open.getKey(), irrelevantOverhead.getKey());
		assertNotEquals(prayed.getKey(), irrelevantOverhead.getKey());

		assertTrue(prayed.isStyleProtected());
		assertFalse(irrelevantOverhead.isStyleProtected());
		assertEquals("Protect from Melee", prayed.getTargetPrayerLabel());
		assertEquals("Retribution", irrelevantOverhead.getTargetPrayerLabel());
		assertEquals("No overhead", open.getTargetPrayerLabel());
	}

	@Test
	public void overheadsSurviveTheSpecialAttackCopy()
	{
		final CombatContext prayed = AttackMatcherTest.builder(CombatStyle.RANGED, 3, 100)
			.targetOverheads(Collections.singletonList("PROTECT_FROM_MISSILES"))
			.styleProtected(true)
			.build();
		final CombatContext spec = prayed.asSpecial();

		assertTrue(spec.isSpecial());
		assertTrue(spec.isStyleProtected());
		assertEquals(prayed.getTargetOverheads(), spec.getTargetOverheads());
		assertEquals(prayed.getNpcId(), spec.getNpcId());
		assertEquals(prayed.getAttackSpeed(), spec.getAttackSpeed());
	}

	@Test
	public void multipleOverheadsAreSortedIntoTheKey()
	{
		final CombatContext a = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Arrays.asList("SMITE", "PROTECT_FROM_MELEE"))
			.styleProtected(true)
			.build();
		final CombatContext b = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Arrays.asList("PROTECT_FROM_MELEE", "SMITE"))
			.styleProtected(true)
			.build();

		assertEquals(a.getKey(), b.getKey());
		assertEquals(Arrays.asList("PROTECT_FROM_MELEE", "SMITE"), b.getTargetOverheads());
		assertEquals("Protect from Melee, Smite", b.getTargetPrayerLabel());
	}

	@Test
	public void prayersAreSortedIntoTheKey()
	{
		final CombatContext a = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 1)
			.prayers(Arrays.asList("PIETY", "PROTECT_FROM_MELEE"))
			.build();
		final CombatContext b = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 1)
			.prayers(Arrays.asList("PROTECT_FROM_MELEE", "PIETY"))
			.build();

		assertEquals(a.getKey(), b.getKey());
		assertEquals(Arrays.asList("PIETY", "PROTECT_FROM_MELEE"), b.getPrayers());
	}

	@Test
	public void contextFromAnOlderFileNeverHandsBackNullLists()
	{
		// This is exactly what crashed the panel on the first upgrade: a history file written
		// before a list field existed. Gson bypasses the constructor, so the field loads as null
		// and every reader of it threw.
		final String olderFormat = "{'npcId':9046,'combatStyle':'MELEE','attackSpeed':4,'key':'abc'}"
			.replace('\'', '"');
		final CombatContext old = new Gson().fromJson(olderFormat, CombatContext.class);

		assertNotNull(old.getPrayers());
		assertNotNull(old.getTargetOverheads());
		assertTrue(old.getPrayers().isEmpty());
		assertTrue(old.getTargetOverheads().isEmpty());
		assertFalse(old.isStyleProtected());
		assertEquals("No overhead", old.getTargetPrayerLabel());
		assertEquals(9046, old.getNpcId());
	}

	@Test
	public void attackLabelIsTheSpellOrTheStyleActuallySelected()
	{
		final CombatContext spell = AttackMatcherTest.builder(CombatStyle.MAGIC, 5, 1)
			.styleIndex(4)
			.styleName("Casting")
			.spellId(46)
			.spellName("Ice Barrage")
			.build();
		final CombatContext staff = AttackMatcherTest.builder(CombatStyle.MAGIC, 4, 1)
			.styleIndex(0)
			.styleName("Magic")
			.build();
		final CombatContext rapid = AttackMatcherTest.builder(CombatStyle.RANGED, 3, 1)
			.styleName("Rapid")
			.build();

		// A spell names itself; everything else is named by the style the combat tab was on,
		// which is far more use than "Melee" or "Ranged".
		assertEquals("Ice Barrage", spell.getAttackLabel());
		assertEquals("Magic", staff.getAttackLabel());
		assertEquals("Rapid", rapid.getAttackLabel());
		assertEquals("Aggressive", AttackMatcherTest.context(CombatStyle.MELEE, 4, 1).getAttackLabel());
	}

	@Test
	public void attackLabelFallsBackWhenTheStyleCouldNotBeRead()
	{
		final CombatContext unknownStyle = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 1)
			.styleName("Unknown")
			.build();
		final CombatContext noStyle = AttackMatcherTest.builder(CombatStyle.RANGED, 4, 1)
			.styleName("")
			.build();

		assertEquals("Melee", unknownStyle.getAttackLabel());
		assertEquals("Ranged", noStyle.getAttackLabel());
	}
}
