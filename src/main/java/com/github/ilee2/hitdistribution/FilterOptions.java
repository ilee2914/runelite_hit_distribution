package com.github.ilee2.hitdistribution;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** The choices the panel's filters offer, derived from what has been recorded. */
@Getter
@AllArgsConstructor
public class FilterOptions
{
	private final List<Option> npcs;
	private final List<Option> attacks;

	/** Equipment slot index to the items seen in that slot, most used first. */
	private final Map<Integer, List<Option>> gearBySlot;

	/** Convenience for the weapon slot, which has a search box of its own. */
	public List<Option> getWeapons()
	{
		return gearBySlot.getOrDefault(CombatContext.WEAPON_SLOT, java.util.Collections.emptyList());
	}

	@Getter
	@AllArgsConstructor
	public static class Option
	{
		/** Text shown in the list. */
		private final String label;

		/** NPC name or attack label, depending on which list this belongs to. */
		@Nullable
		private final String name;

		/** NPC id or item id, depending on which list this belongs to. */
		@Nullable
		private final Integer id;

		/** How many attacks match, for ordering and for showing where the data is. */
		private final int attacks;

		@Override
		public String toString()
		{
			return label;
		}
	}
}
