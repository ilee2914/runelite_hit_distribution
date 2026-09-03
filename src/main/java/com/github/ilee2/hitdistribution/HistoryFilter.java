package com.github.ilee2.hitdistribution;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;

/** Which contexts and fights to fold into an {@link Aggregate}. A null or absent field means "any". */
@Getter
public class HistoryFilter
{
	public static final HistoryFilter ALL = new HistoryFilter(null, null, null, null, null);

	/** NPC display name, matched via the store's id-to-name map. */
	@Nullable
	private final String npcName;

	/** Exact NPC id, for when phases or forms of one name are being split apart. */
	@Nullable
	private final Integer npcId;

	/**
	 * Equipment slot index to the item id required in it. A slot that is absent is not filtered
	 * on. Every worn slot moves the damage distribution, so all of them can be filtered, and the
	 * weapon is simply slot {@link CombatContext#WEAPON_SLOT} rather than a field of its own.
	 */
	private final Map<Integer, Integer> gear;

	/** {@link CombatContext#getAttackLabel()} value, e.g. "Melee" or "Ice Barrage". */
	@Nullable
	private final String attackLabel;

	/**
	 * Whether the target was praying against the style used. TRUE keeps only protected attacks,
	 * FALSE only unprotected ones, null keeps both.
	 */
	@Nullable
	private final Boolean styleProtected;

	public HistoryFilter(@Nullable String npcName, @Nullable Integer npcId,
		@Nullable Map<Integer, Integer> gear, @Nullable String attackLabel, @Nullable Boolean styleProtected)
	{
		this.npcName = npcName;
		this.npcId = npcId;
		this.gear = gear == null || gear.isEmpty()
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new HashMap<>(gear));
		this.attackLabel = attackLabel;
		this.styleProtected = styleProtected;
	}

	/** Convenience for the weapon slot, which is the one most things care about. */
	@Nullable
	public Integer getWeaponId()
	{
		return gear.get(CombatContext.WEAPON_SLOT);
	}

	/** @return this filter with the monster dimension dropped. */
	HistoryFilter withoutNpc()
	{
		return new HistoryFilter(null, null, gear, attackLabel, styleProtected);
	}

	/** @return this filter with the attack dimension dropped. */
	HistoryFilter withoutAttack()
	{
		return new HistoryFilter(npcName, npcId, gear, null, styleProtected);
	}

	/** @return this filter with one equipment slot dropped. */
	HistoryFilter withoutSlot(int slot)
	{
		if (!gear.containsKey(slot))
		{
			return this;
		}
		final Map<Integer, Integer> without = new HashMap<>(gear);
		without.remove(slot);
		return new HistoryFilter(npcName, npcId, without, attackLabel, styleProtected);
	}
}
