package com.github.ilee2.hitdistribution;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(HitDistributionConfig.GROUP)
public interface HitDistributionConfig extends Config
{
	String GROUP = "hitdistribution";

	@ConfigSection(
		name = "Display",
		description = "How the history panel groups and shows what it has recorded",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Tracking",
		description = "How hits are matched to attacks. The defaults suit almost everything.",
		position = 1
	)
	String trackingSection = "tracking";

	// ---------------------------------------------------------------- display

	@ConfigItem(
		keyName = "defaultScope",
		name = "Opens on",
		description = "Which view the panel starts on: only what has been recorded since you logged in, or the whole saved history. Either can be picked in the panel at any time.",
		position = 0,
		section = displaySection
	)
	default HistoryScope defaultScope()
	{
		return HistoryScope.ALL_TIME;
	}

	@ConfigItem(
		keyName = "includeKillingBlows",
		name = "Count killing blows",
		description = "Chart the hit that kills a monster. It is capped by the monster's remaining hitpoints, so leaving it out shows what the weapon really rolls. Total damage, accuracy and DPS always count it. Can also be toggled under the chart.",
		position = 1,
		section = displaySection
	)
	default boolean includeKillingBlows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "splitNpcById",
		name = "Split monsters by id",
		description = "Show one entry per NPC id instead of one per name, so phases or forms that share a name can be told apart.",
		position = 2,
		section = displaySection
	)
	default boolean splitNpcById()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showContexts",
		name = "Show damage history",
		description = "List the individual hits matching the filter under the chart.",
		position = 3,
		section = displaySection
	)
	default boolean showContexts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxContexts",
		name = "History rows",
		description = "Most hits to list under the chart.",
		position = 4,
		section = displaySection
	)
	@Range(min = 5, max = 200)
	default int maxContexts()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "hitLogSize",
		name = "Hits kept",
		description = "How many individual hits to keep in the history file. The statistics and the chart are unaffected; they are built from totals that are never dropped.",
		position = 5,
		section = displaySection
	)
	@Range(min = 50, max = 5000)
	default int hitLogSize()
	{
		return 500;
	}

	// --------------------------------------------------------------- tracking

	@ConfigItem(
		keyName = "hitWindowTicks",
		name = "Hit window",
		description = "How many ticks after an attack animation its hitsplat or splash may still arrive. Long-range projectiles need the most.",
		position = 0,
		section = trackingSection
	)
	@Range(min = 1, max = 20)
	@Units(Units.TICKS)
	default int hitWindowTicks()
	{
		return 6;
	}

	@ConfigItem(
		keyName = "idleGapTicks",
		name = "Idle gap",
		description = "A pause between attacks longer than this is treated as leaving combat rather than as wasted ticks.",
		position = 1,
		section = trackingSection
	)
	@Range(min = 2, max = 50)
	@Units(Units.TICKS)
	default int idleGapTicks()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "autosaveMinutes",
		name = "Autosave interval",
		description = "How often the history file is written while logged in. It is always written on logout.",
		position = 2,
		section = trackingSection
	)
	@Range(min = 1, max = 30)
	@Units(Units.MINUTES)
	default int autosaveMinutes()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "debugLog",
		name = "Debug logging",
		description = "Write every attack, hit and splash decision to the client log.",
		position = 3,
		section = trackingSection
	)
	default boolean debugLog()
	{
		return false;
	}
}
