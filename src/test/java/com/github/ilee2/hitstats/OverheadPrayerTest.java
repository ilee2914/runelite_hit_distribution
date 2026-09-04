package com.github.ilee2.hitstats;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OverheadPrayerTest
{
	@Test
	public void spriteIndexFollowsHeadIconOrder()
	{
		assertSame(OverheadPrayer.PROTECT_FROM_MELEE, OverheadPrayer.forSpriteIndex(0));
		assertSame(OverheadPrayer.PROTECT_FROM_MISSILES, OverheadPrayer.forSpriteIndex(1));
		assertSame(OverheadPrayer.PROTECT_FROM_MAGIC, OverheadPrayer.forSpriteIndex(2));
		assertSame(OverheadPrayer.RETRIBUTION, OverheadPrayer.forSpriteIndex(3));
		assertSame(OverheadPrayer.PROTECT_ALL, OverheadPrayer.forSpriteIndex(9));
		assertSame(OverheadPrayer.DEFLECT_MAGIC, OverheadPrayer.forSpriteIndex(14));
	}

	@Test
	public void unknownSpriteIndexIsNotGuessedAt()
	{
		assertNull(OverheadPrayer.forSpriteIndex(-1));
		assertNull(OverheadPrayer.forSpriteIndex(15));
		assertNull(OverheadPrayer.forSpriteIndex(999));
	}

	@Test
	public void protectionPrayersBlockTheirOwnStyleOnly()
	{
		assertTrue(OverheadPrayer.PROTECT_FROM_MELEE.blocks(CombatStyle.MELEE));
		assertFalse(OverheadPrayer.PROTECT_FROM_MELEE.blocks(CombatStyle.RANGED));
		assertFalse(OverheadPrayer.PROTECT_FROM_MELEE.blocks(CombatStyle.MAGIC));

		assertTrue(OverheadPrayer.PROTECT_FROM_MAGIC.blocks(CombatStyle.MAGIC));
		assertFalse(OverheadPrayer.PROTECT_FROM_MAGIC.blocks(CombatStyle.MELEE));

		assertTrue(OverheadPrayer.DEFLECT_MISSILES.blocks(CombatStyle.RANGED));
		assertFalse(OverheadPrayer.DEFLECT_MISSILES.blocks(CombatStyle.MAGIC));
	}

	@Test
	public void combinedPrayersBlockEveryStyleTheyCover()
	{
		assertTrue(OverheadPrayer.PROTECT_RANGE_MAGE.blocks(CombatStyle.RANGED));
		assertTrue(OverheadPrayer.PROTECT_RANGE_MAGE.blocks(CombatStyle.MAGIC));
		assertFalse(OverheadPrayer.PROTECT_RANGE_MAGE.blocks(CombatStyle.MELEE));

		for (CombatStyle style : new CombatStyle[]{CombatStyle.MELEE, CombatStyle.RANGED, CombatStyle.MAGIC})
		{
			assertTrue(style.name(), OverheadPrayer.PROTECT_ALL.blocks(style));
		}
	}

	@Test
	public void nonProtectiveOverheadsBlockNothing()
	{
		for (OverheadPrayer prayer : new OverheadPrayer[]{OverheadPrayer.RETRIBUTION, OverheadPrayer.SMITE,
			OverheadPrayer.REDEMPTION, OverheadPrayer.WRATH, OverheadPrayer.SOUL_SPLIT})
		{
			assertFalse(prayer.name(), prayer.isProtective());
			assertFalse(prayer.name(), prayer.blocks(CombatStyle.MELEE));
			assertFalse(prayer.name(), prayer.blocks(CombatStyle.RANGED));
			assertFalse(prayer.name(), prayer.blocks(CombatStyle.MAGIC));
		}
		assertTrue(OverheadPrayer.PROTECT_FROM_MELEE.isProtective());
	}

	@Test
	public void readerAsksWhetherAnyOverheadBlocksTheStyle()
	{
		final Set<OverheadPrayer> melee = EnumSet.of(OverheadPrayer.PROTECT_FROM_MELEE);
		assertTrue(OverheadPrayerReader.blocks(melee, CombatStyle.MELEE));
		assertFalse(OverheadPrayerReader.blocks(melee, CombatStyle.MAGIC));

		final Set<OverheadPrayer> several = EnumSet.of(OverheadPrayer.RETRIBUTION, OverheadPrayer.PROTECT_FROM_MAGIC);
		assertTrue(OverheadPrayerReader.blocks(several, CombatStyle.MAGIC));
		assertFalse(OverheadPrayerReader.blocks(several, CombatStyle.RANGED));

		assertFalse(OverheadPrayerReader.blocks(Collections.emptySet(), CombatStyle.MELEE));
	}

	@Test
	public void unknownStyleIsNeverTreatedAsBlocked()
	{
		// A weapon the style resolver could not read must not be recorded as praying-blocked;
		// that would quietly move real hits into the protected bucket.
		assertFalse(OverheadPrayerReader.blocks(EnumSet.allOf(OverheadPrayer.class), CombatStyle.UNKNOWN));
	}

	@Test
	public void labelsAreLookedUpByEnumNameAndFallBackToTheRawValue()
	{
		assertEquals("Protect from Melee", OverheadPrayer.labelFor("PROTECT_FROM_MELEE"));
		assertEquals("Protect from All", OverheadPrayer.labelFor("PROTECT_ALL"));
		assertEquals("SOMETHING_NEW", OverheadPrayer.labelFor("SOMETHING_NEW"));
	}
}
