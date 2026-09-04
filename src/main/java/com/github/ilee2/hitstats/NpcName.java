package com.github.ilee2.hitstats;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Display name for an NPC id, learned the first time the id is fought. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NpcName
{
	private String name;
	private int combatLevel;
}
