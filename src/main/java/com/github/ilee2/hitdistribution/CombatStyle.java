package com.github.ilee2.hitdistribution;

import lombok.Getter;

/** What an attack deals its damage with. */
@Getter
public enum CombatStyle
{
	MELEE("Melee"),
	RANGED("Ranged"),
	MAGIC("Magic"),
	UNKNOWN("Unknown");

	private final String label;

	CombatStyle(String label)
	{
		this.label = label;
	}
}
