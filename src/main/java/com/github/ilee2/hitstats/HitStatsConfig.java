package com.github.ilee2.hitstats;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(HitStatsConfig.GROUP)
public interface HitStatsConfig extends Config
{
	String GROUP = "hitstats";

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

	@ConfigSection(
		name = "Community",
		description = "Compare your hits with other players of this plugin. Off by default.",
		position = 2,
		closedByDefault = true
	)
	String communitySection = "community";

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
		keyName = "countProtectedAttacks",
		name = "Count attacks into protection",
		description = "Include attacks made while the target was praying against the style you were using. It takes far less damage from those, so leaving them out shows what your setup does to a target that is not protecting. Can also be toggled in the panel.",
		position = 2,
		section = displaySection
	)
	default boolean countProtectedAttacks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "splitNpcById",
		name = "Split monsters by id",
		description = "Show one entry per NPC id instead of one per name, so phases or forms that share a name can be told apart.",
		position = 3,
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
		position = 4,
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
		position = 5,
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
		position = 6,
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

	// -------------------------------------------------------------- community

	@ConfigItem(
		keyName = "uploadEnabled",
		name = "Share my hits",
		description = "Share your hit statistics with other players of this plugin, and see how"
			+ " your damage distribution, accuracy, DPS and wasted ticks compare with everyone"
			+ " else's using the same setup."
			+ " What is sent: for each combination of worn gear, Attack/Strength/Ranged/Magic"
			+ " levels, damage prayers, attack style, spell, special attack, target monster and"
			+ " the monster's overhead prayer, the count of hits at each damage value and the"
			+ " totals behind the panel's statistics, plus the item and monster names for the ids"
			+ " involved. A random id identifies your history file so your own numbers can be"
			+ " updated; it is not derived from your account."
			+ " Your character name, account, world, location, chat and other players are never"
			+ " sent. Nothing is sent while this is off.",
		position = 0,
		section = communitySection
	)
	default boolean uploadEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "uploadMinutes",
		name = "Share every",
		description = "How often your statistics are sent while you are logged in. They are also"
			+ " sent when you log out, when you close the client, and once after you log in to"
			+ " catch up anything a crash interrupted. Nothing is ever sent per attack.",
		position = 1,
		section = communitySection
	)
	@Range(min = 15, max = 120)
	@Units(Units.MINUTES)
	default int uploadMinutes()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "showCommunity",
		name = "Show the community chart",
		description = "Draw everyone else's distribution as a line across your own bars, and compare"
			+ " the summary statistics, whenever the filter names a monster and a weapon."
			+ " This asks the community server for an average whenever your filter changes, so it"
			+ " is off until you turn it on. The request carries only the filter itself: the"
			+ " monster, the gear, the attack and your levels. You do not have to share anything"
			+ " to use it.",
		position = 2,
		section = communitySection
	)
	default boolean showCommunity()
	{
		return false;
	}

	@ConfigItem(
		keyName = "levelMatch",
		name = "Match levels",
		description = "Which other players to compare against. The bracket is the five-level band"
			+ " of the skill that drives your style's damage, so 95 to 99 is one bracket. Exact"
			+ " matching is the most like for like and finds the fewest players.",
		position = 3,
		section = communitySection
	)
	default LevelMatch levelMatch()
	{
		return LevelMatch.BRACKET;
	}

	@ConfigItem(
		keyName = "installId",
		name = "",
		description = "",
		hidden = true,
		section = communitySection,
		position = 98
	)
	default String installId()
	{
		return "";
	}

	@ConfigItem(
		keyName = "serverUrl",
		name = "",
		description = "",
		hidden = true,
		section = communitySection,
		position = 99
	)
	default String serverUrl()
	{
		return "";
	}
}
