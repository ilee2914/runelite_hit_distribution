package com.github.ilee2.hitdistribution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Pairs attack animations with the hitsplats and splashes they produce. Pure tick arithmetic, no
 * client access, so it can be unit tested.
 *
 * <p>Rules:
 * <ul>
 *   <li>An animation inside the cooldown of the last committed attack is not an attack (it is a
 *       block, an eat, or similar) and is dropped.</li>
 *   <li>A hit on an NPC prefers the oldest pending attack on that NPC that has not resolved yet,
 *       or one that resolved on this same tick (multi-hit weapons). Failing that it takes any
 *       unresolved pending attack (area-of-effect spill onto a secondary target), then any attack
 *       resolved on this tick.</li>
 *   <li>A splash only matches a magic attack on that same NPC. The splash graphic carries no
 *       owner, so it may have been another player's: an attack resolved by a splash stays open to
 *       a later hit of ours on the same NPC, and that hit is the attack's real result. The
 *       tracker then takes the splash back.</li>
 *   <li>Committing an attack retroactively drops uncommitted animations that fell inside its
 *       cooldown.</li>
 *   <li>Pending attacks older than the window are expired; those never matched are counted.</li>
 * </ul>
 */
public class AttackMatcher
{
	private int windowTicks;
	private final Deque<PendingAttack> pending = new ArrayDeque<>();

	@Nullable
	private PendingAttack lastCommitted;

	@Getter
	private int rejectedByCooldown;

	@Getter
	private int expiredUnmatched;

	public AttackMatcher(int windowTicks)
	{
		this.windowTicks = windowTicks;
	}

	/** Changes the window without dropping what is pending, so a config edit mid-fight loses nothing. */
	public void setWindowTicks(int windowTicks)
	{
		this.windowTicks = windowTicks;
	}

	public int getWindowTicks()
	{
		return windowTicks;
	}

	/**
	 * @return whether an animation on {@code tick} falls inside the cooldown of the last committed
	 * attack, and so would be rejected by {@link #offer}. Lets the caller skip building a context
	 * it is about to throw away.
	 */
	public boolean inCooldown(int tick)
	{
		return lastCommitted != null && tick - lastCommitted.getTick() < lastCommitted.getSpeed();
	}

	/** @return false if the animation cannot be an attack and was dropped. */
	public boolean offer(PendingAttack attack)
	{
		if (inCooldown(attack.getTick()))
		{
			rejectedByCooldown++;
			return false;
		}

		// Two animations on one tick: the later one is what the player is actually doing.
		final PendingAttack last = pending.peekLast();
		if (last != null && last.getTick() == attack.getTick() && !last.isCommitted())
		{
			pending.pollLast();
		}

		pending.addLast(attack);
		return true;
	}

	@Nullable
	public PendingAttack matchHit(int tick, int npcIndex)
	{
		PendingAttack sameTarget = null;
		PendingAttack sameTargetSplashed = null;
		PendingAttack anyUnresolved = null;
		PendingAttack anyThisTick = null;

		for (PendingAttack a : pending)
		{
			if (!inWindow(a, tick))
			{
				continue;
			}

			final boolean open = a.getResolvedTick() < 0 || a.getResolvedTick() == tick;
			if (a.getNpcIndex() == npcIndex)
			{
				if (open)
				{
					sameTarget = a;
					break;
				}
				// Resolved by a splash is a weaker kind of open: the splash may have been
				// somebody else's, and this hit its real result. Only a fallback, so that a
				// splashed attack never takes the hit of a newer attack on the same target.
				if (a.isResolvedBySplash() && sameTargetSplashed == null)
				{
					sameTargetSplashed = a;
				}
				continue;
			}

			if (a.getResolvedTick() < 0)
			{
				if (anyUnresolved == null)
				{
					anyUnresolved = a;
				}
			}
			else if (a.getResolvedTick() == tick && anyThisTick == null)
			{
				anyThisTick = a;
			}
		}

		if (sameTarget == null)
		{
			sameTarget = sameTargetSplashed;
		}
		if (sameTarget != null)
		{
			sameTarget.setResolvedTick(tick);
			return commit(sameTarget);
		}
		if (anyUnresolved != null)
		{
			// Spill onto a secondary target does not resolve the attack; its primary hit may
			// still be in flight.
			return commit(anyUnresolved);
		}
		if (anyThisTick != null)
		{
			return commit(anyThisTick);
		}
		return null;
	}

	@Nullable
	public PendingAttack matchSplash(int tick, int npcIndex)
	{
		for (PendingAttack a : pending)
		{
			if (inWindow(a, tick) && a.isMagic() && a.getNpcIndex() == npcIndex && a.getResolvedTick() < 0)
			{
				a.setResolvedTick(tick);
				a.setResolvedBySplash(true);
				return commit(a);
			}
		}
		return null;
	}

	/**
	 * @return the tick the most recent attack on {@code npcIndex} resolved on, or -1 if none is
	 * pending or resolved. Diagnostic: tells a debug log how far a stray hitsplat landed from the
	 * attack it might have belonged to.
	 */
	public int lastResolvedTick(int npcIndex)
	{
		int last = -1;
		for (PendingAttack a : pending)
		{
			if (a.getNpcIndex() == npcIndex && a.getResolvedTick() > last)
			{
				last = a.getResolvedTick();
			}
		}
		return last;
	}

	/** @return the pending attack made on {@code tick}, if any, so a late spec-energy update can flag it. */
	@Nullable
	public PendingAttack findByTick(int tick)
	{
		for (PendingAttack a : pending)
		{
			if (a.getTick() == tick)
			{
				return a;
			}
		}
		return null;
	}

	/** Drops attacks that have fallen out of the window. @return those that never matched. */
	public List<PendingAttack> expire(int tick)
	{
		final List<PendingAttack> unmatched = new ArrayList<>();
		final Iterator<PendingAttack> it = pending.iterator();
		while (it.hasNext())
		{
			final PendingAttack a = it.next();
			if (tick - a.getTick() > windowTicks)
			{
				it.remove();
				if (!a.isCommitted())
				{
					expiredUnmatched++;
					unmatched.add(a);
				}
			}
		}
		return unmatched;
	}

	public void reset()
	{
		pending.clear();
		lastCommitted = null;
	}

	int pendingCount()
	{
		return pending.size();
	}

	private boolean inWindow(PendingAttack a, int tick)
	{
		return a.getTick() <= tick && tick - a.getTick() <= windowTicks;
	}

	private PendingAttack commit(PendingAttack attack)
	{
		if (attack.isCommitted())
		{
			return attack;
		}

		attack.setCommitted(true);
		if (lastCommitted == null || attack.getTick() >= lastCommitted.getTick())
		{
			lastCommitted = attack;
		}

		// Anything animated inside this attack's cooldown was not an attack.
		final int cooldownEnd = attack.getTick() + attack.getSpeed();
		pending.removeIf(other -> other != attack
			&& !other.isCommitted()
			&& other.getTick() > attack.getTick()
			&& other.getTick() < cooldownEnd);

		return attack;
	}
}
