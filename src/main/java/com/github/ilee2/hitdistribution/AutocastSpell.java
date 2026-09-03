package com.github.ilee2.hitdistribution;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Names for the values of {@code VarPlayerID.AUTOCAST_SPELL} (varp 276), which holds the
 * currently selected autocast spell. Zero means no spell is selected.
 */
final class AutocastSpell
{
	private static final Map<Integer, String> NAMES = new HashMap<>();
	private static final Map<String, Integer> IDS = new HashMap<>();

	static
	{
		put(1, "Wind Strike");
		put(2, "Water Strike");
		put(3, "Earth Strike");
		put(4, "Fire Strike");
		put(5, "Wind Bolt");
		put(6, "Water Bolt");
		put(7, "Earth Bolt");
		put(8, "Fire Bolt");
		put(9, "Wind Blast");
		put(10, "Water Blast");
		put(11, "Earth Blast");
		put(12, "Fire Blast");
		put(13, "Wind Wave");
		put(14, "Water Wave");
		put(15, "Earth Wave");
		put(16, "Fire Wave");
		put(17, "Crumble Undead");
		put(18, "Magic Dart");
		put(19, "Claws of Guthix");
		put(20, "Flames of Zamorak");
		put(31, "Smoke Rush");
		put(32, "Shadow Rush");
		put(33, "Blood Rush");
		put(34, "Ice Rush");
		put(35, "Smoke Burst");
		put(36, "Shadow Burst");
		put(37, "Blood Burst");
		put(38, "Ice Burst");
		put(39, "Smoke Blitz");
		put(40, "Shadow Blitz");
		put(41, "Blood Blitz");
		put(42, "Ice Blitz");
		put(43, "Smoke Barrage");
		put(44, "Shadow Barrage");
		put(45, "Blood Barrage");
		put(46, "Ice Barrage");
		put(47, "Iban Blast");
		put(48, "Wind Surge");
		put(49, "Water Surge");
		put(50, "Earth Surge");
		put(51, "Fire Surge");
		put(52, "Saradomin Strike");
		put(53, "Inferior Demonbane");
		put(54, "Superior Demonbane");
		put(55, "Dark Demonbane");
		put(56, "Ghostly Grasp");
		put(57, "Skeletal Grasp");
		put(58, "Undead Grasp");
	}

	private AutocastSpell()
	{
	}

	private static void put(int id, String name)
	{
		NAMES.put(id, name);
		IDS.put(normalise(name), id);
	}

	/** @return the spell's name, or a placeholder for an id this table does not know. */
	static String name(int autocastId)
	{
		final String name = NAMES.get(autocastId);
		return name != null ? name : "Spell #" + autocastId;
	}

	/** @return the autocast id for a spell name as shown in the game, or -1 if unknown. */
	static int id(@Nullable String name)
	{
		if (name == null)
		{
			return -1;
		}
		final Integer id = IDS.get(normalise(name));
		return id != null ? id : -1;
	}

	/**
	 * Whether the spell belongs to the standard spellbook. Those are the only spells a harmonised
	 * nightmare staff speeds up.
	 */
	static boolean isStandardSpellbook(int autocastId)
	{
		return (autocastId >= 1 && autocastId <= 20)
			|| autocastId == 47
			|| (autocastId >= 48 && autocastId <= 52);
	}

	private static String normalise(String name)
	{
		// The game writes "Iban's Blast"; the table stores it without the possessive.
		return name.trim().toLowerCase(Locale.ROOT).replace("iban's", "iban");
	}
}
