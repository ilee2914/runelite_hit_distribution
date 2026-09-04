package com.github.ilee2.hitstats;

import lombok.Getter;
import lombok.Setter;

/**
 * An attack animation seen on the player, waiting for its hitsplat or splash. Snapshotting the
 * context here rather than when the hit lands is what keeps a gear switch during a projectile's
 * flight from crediting the wrong weapon.
 */
@Getter
public class PendingAttack
{
	private final int tick;
	private final int npcIndex;
	private final CombatContext context;

	/** Set once a hitsplat or splash has been matched to this attack. */
	@Setter
	private boolean committed;

	/** Tick the primary target's hit or splash landed on, or -1 while still waiting. */
	@Setter
	private int resolvedTick = -1;

	/**
	 * Set when what resolved this attack was a splash graphic rather than a hitsplat. A splash
	 * carries no owner, so a hit of ours on the same target arriving afterwards means the splash
	 * was another player's; the tracker clears this once it has taken the splash back.
	 */
	@Setter
	private boolean resolvedBySplash;

	/** Set by the tracker once the attack has been counted in the store. */
	@Setter
	private boolean recorded;

	public PendingAttack(int tick, int npcIndex, CombatContext context)
	{
		this.tick = tick;
		this.npcIndex = npcIndex;
		this.context = context;
	}

	int getSpeed()
	{
		return context.getAttackSpeed();
	}

	boolean isMagic()
	{
		return context.getCombatStyle() == CombatStyle.MAGIC;
	}
}
