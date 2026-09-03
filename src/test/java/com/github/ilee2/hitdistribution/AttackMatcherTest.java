package com.github.ilee2.hitdistribution;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AttackMatcherTest
{
	private static final int WINDOW = 6;

	@Test
	public void hitMatchesAttackOnSameTarget()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		assertTrue(m.offer(a));

		assertSame(a, m.matchHit(11, 1));
		assertTrue(a.isCommitted());
		assertEquals(11, a.getResolvedTick());
	}

	@Test
	public void hitOnSameTickAsAnimationMatches()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(a);
		assertSame(a, m.matchHit(10, 1));
	}

	@Test
	public void hitOutsideWindowDoesNotMatch()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		m.offer(attack(10, 1, CombatStyle.RANGED, 3));
		assertNull(m.matchHit(17, 1));
	}

	@Test
	public void animationInsideCooldownIsRejected()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(a);
		m.matchHit(11, 1);

		// A block animation two ticks later cannot be an attack with a 4-tick weapon.
		assertFalse(m.offer(attack(12, 1, CombatStyle.MELEE, 4)));
		assertEquals(1, m.getRejectedByCooldown());

		// The next real attack at the cooldown boundary is accepted.
		assertTrue(m.offer(attack(14, 1, CombatStyle.MELEE, 4)));
	}

	@Test
	public void committingDropsUncommittedAnimationsInsideCooldown()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.RANGED, 4);
		final PendingAttack block = attack(11, 1, CombatStyle.RANGED, 4);
		m.offer(a);
		m.offer(block);

		// Projectile lands at 13: it belongs to the first attack, and the block is discarded.
		assertSame(a, m.matchHit(13, 1));
		assertEquals(1, m.pendingCount());

		final PendingAttack next = attack(14, 1, CombatStyle.RANGED, 4);
		assertTrue(m.offer(next));
		assertSame(next, m.matchHit(17, 1));
	}

	@Test
	public void secondHitOnSameTargetGoesToNextAttack()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 4);
		final PendingAttack b = attack(14, 1, CombatStyle.MAGIC, 4);
		m.offer(a);
		assertSame(a, m.matchHit(12, 1));
		m.offer(b);
		assertSame(b, m.matchHit(16, 1));
	}

	@Test
	public void multiHitWeaponLandsSeveralHitsplatsOnOneAttack()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 5);
		m.offer(a);
		assertSame(a, m.matchHit(11, 1));
		assertSame(a, m.matchHit(11, 1));
		assertSame(a, m.matchHit(11, 1));
	}

	@Test
	public void areaSpillOnOtherNpcMatchesTheAttack()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 5);
		m.offer(a);
		assertSame(a, m.matchHit(12, 1));
		assertSame(a, m.matchHit(12, 2));
		assertSame(a, m.matchHit(12, 3));
	}

	@Test
	public void splashMatchesOnlyMagicOnSameTarget()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack melee = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(melee);
		assertNull(m.matchSplash(11, 1));

		final AttackMatcher m2 = new AttackMatcher(WINDOW);
		final PendingAttack magic = attack(10, 1, CombatStyle.MAGIC, 5);
		m2.offer(magic);
		assertNull(m2.matchSplash(12, 2));
		assertSame(magic, m2.matchSplash(12, 1));
		assertTrue(magic.isCommitted());
	}

	@Test
	public void splashedAttackDoesNotAbsorbNextHit()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 4);
		m.offer(a);
		assertSame(a, m.matchSplash(12, 1));

		final PendingAttack b = attack(14, 1, CombatStyle.MAGIC, 4);
		m.offer(b);
		assertSame(b, m.matchHit(16, 1));
	}

	@Test
	public void expireReportsUnmatchedAnimations()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack eat = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(eat);

		assertTrue(m.expire(16).isEmpty());
		final List<PendingAttack> expired = m.expire(17);
		assertEquals(Collections.singletonList(eat), expired);
		assertEquals(1, m.getExpiredUnmatched());
		assertEquals(0, m.pendingCount());
	}

	@Test
	public void laterAnimationOnSameTickReplacesEarlier()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack first = attack(10, 1, CombatStyle.MELEE, 4);
		final PendingAttack second = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(first);
		m.offer(second);
		assertEquals(1, m.pendingCount());
		assertSame(second, m.matchHit(11, 1));
	}

	@Test
	public void findByTickReturnsThePendingAttack()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(a);
		assertSame(a, m.findByTick(10));
		assertNull(m.findByTick(11));
	}

	@Test
	public void inCooldownAgreesWithOffer()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		assertFalse(m.inCooldown(10));

		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(a);
		// Nothing committed yet, so nothing to be inside the cooldown of.
		assertFalse(m.inCooldown(12));

		m.matchHit(11, 1);
		assertTrue(m.inCooldown(12));
		assertTrue(m.inCooldown(13));
		assertFalse(m.inCooldown(14));
		assertFalse(m.offer(attack(13, 1, CombatStyle.MELEE, 4)));
		assertTrue(m.offer(attack(14, 1, CombatStyle.MELEE, 4)));
	}

	@Test
	public void changingTheWindowKeepsPendingAttacks()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.RANGED, 5);
		m.offer(a);

		m.setWindowTicks(10);
		assertEquals(10, m.getWindowTicks());
		assertEquals(1, m.pendingCount());
		assertTrue(m.expire(17).isEmpty());
		assertSame(a, m.matchHit(19, 1));
	}

	@Test
	public void hitAfterSplashReopensTheAttack()
	{
		// Another player's splash lands on our target while our spell is in the air, then our
		// own hit arrives: the hit is the attack's real result and the splash was not ours.
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 5);
		m.offer(a);

		assertSame(a, m.matchSplash(11, 1));
		assertTrue(a.isResolvedBySplash());

		assertSame(a, m.matchHit(13, 1));
		assertEquals(13, a.getResolvedTick());
		// The flag stays up for the tracker to read and take the splash back.
		assertTrue(a.isResolvedBySplash());
		a.setResolvedBySplash(false);

		// Resolved by a hitsplat now, so a later stray hit goes elsewhere.
		assertNull(m.matchHit(15, 1));
	}

	@Test
	public void splashedAttackYieldsToANewerOpenAttackOnTheSameTarget()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 5);
		m.offer(a);
		assertSame(a, m.matchSplash(12, 1));

		// The next cast is in the air. Its hit is its own, not a reason to take the splash back.
		final PendingAttack b = attack(15, 1, CombatStyle.MAGIC, 5);
		m.offer(b);
		assertSame(b, m.matchHit(16, 1));
		assertTrue(a.isResolvedBySplash());
		assertFalse(b.isResolvedBySplash());
	}

	@Test
	public void splashedAttackDoesNotReopenForAnotherTarget()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		final PendingAttack a = attack(10, 1, CombatStyle.MAGIC, 5);
		m.offer(a);
		assertSame(a, m.matchSplash(11, 1));

		// A hit on a different NPC two ticks after the splash is not area spill from this
		// attack (spill lands on the splash's own tick) and must not be taken as its hit.
		assertNull(m.matchHit(13, 2));
	}

	@Test
	public void lastResolvedTickReportsTheNewestOnThatTarget()
	{
		final AttackMatcher m = new AttackMatcher(WINDOW);
		assertEquals(-1, m.lastResolvedTick(1));

		final PendingAttack a = attack(10, 1, CombatStyle.MELEE, 4);
		m.offer(a);
		assertEquals(-1, m.lastResolvedTick(1));

		m.matchHit(11, 1);
		assertEquals(11, m.lastResolvedTick(1));
		assertEquals(-1, m.lastResolvedTick(2));
	}

	static PendingAttack attack(int tick, int npcIndex, CombatStyle style, int speed)
	{
		return new PendingAttack(tick, npcIndex, context(style, speed, 100));
	}

	static CombatContext context(CombatStyle style, int speed, int npcId)
	{
		return builder(style, speed, npcId).build();
	}

	/** A context with everything filled in, ready for a test to override one field. */
	static CombatContext.CombatContextBuilder builder(CombatStyle style, int speed, int npcId)
	{
		final int[] gear = new int[CombatContext.GEAR_SLOTS];
		Arrays.fill(gear, -1);
		gear[CombatContext.WEAPON_SLOT] = 4151;
		final int[] levels = {99, 99, 99, 99, 99, 99, 99};

		return CombatContext.builder()
			.gear(gear)
			.boosted(levels)
			.real(levels)
			.prayers(Collections.emptyList())
			.weaponCategory(1)
			.styleIndex(1)
			.styleName("Aggressive")
			.combatStyle(style)
			.spellId(0)
			.spellName(null)
			.special(false)
			.npcId(npcId)
			.targetOverheads(Collections.emptyList())
			.styleProtected(false)
			.attackSpeed(speed);
	}
}
