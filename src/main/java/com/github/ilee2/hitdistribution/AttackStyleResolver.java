package com.github.ilee2.hitdistribution;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Works out which combat style the player's current weapon and attack-style selection deals
 * damage with, and what the selected style is called.
 *
 * <p>The style list is read out of the game's own cache the same way the client's Attack Styles
 * plugin does it, rather than from a hardcoded weapon table, so new weapon categories keep working
 * without a plugin update:
 *
 * <pre>
 *   enum 3908 : weapon category -&gt; enum of attack-style structs
 *   struct    : param 1407 -&gt; style name ("Accurate", "Ranging", "Casting", ...)
 * </pre>
 */
@Slf4j
@Singleton
class AttackStyleResolver
{
	/** Weapon category -> id of the enum listing that category's attack-style structs. */
	private static final int WEAPON_STYLES_ENUM = 3908;

	/** Struct param holding an attack style's name. */
	private static final int STYLE_NAME_PARAM = 1407;

	/**
	 * Categories missing from enum 3908; the client hardcodes these too. 22 is the bladed/staff
	 * style list (four melee styles plus the two casting slots), 30 is a plain melee list.
	 */
	private static final int CATEGORY_STAFF_FALLBACK = 22;
	private static final int CATEGORY_MELEE_FALLBACK = 30;

	private static final StyleName[] STAFF_FALLBACK_STYLES = {
		StyleName.ACCURATE, StyleName.AGGRESSIVE, null, StyleName.DEFENSIVE,
		StyleName.CASTING, StyleName.DEFENSIVE_CASTING,
	};

	private static final StyleName[] MELEE_FALLBACK_STYLES = {
		StyleName.ACCURATE, StyleName.AGGRESSIVE, StyleName.AGGRESSIVE, StyleName.DEFENSIVE,
	};

	private final Client client;

	@Getter
	private CombatStyle currentStyle = CombatStyle.UNKNOWN;

	/** Display name of the selected style, e.g. "Rapid" or "Aggressive". */
	@Getter
	private String currentStyleName = "Unknown";

	/** Whether the selected style is Rapid, which takes a tick off the weapon's attack speed. */
	@Getter
	private boolean rapid;

	@Getter
	private int weaponCategory = -1;

	@Getter
	private int styleIndex = -1;

	private int lastCastingMode = -1;

	@Inject
	AttackStyleResolver(Client client)
	{
		this.client = client;
	}

	/**
	 * Recomputes the current style from the varbits. Cheap to call every tick: the cache walk
	 * only runs when one of the three inputs actually moved.
	 */
	void update()
	{
		final int category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		final int index = client.getVarpValue(VarPlayerID.COM_MODE);
		final int castingMode = client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE);

		if (category == weaponCategory && index == styleIndex && castingMode == lastCastingMode)
		{
			return;
		}

		weaponCategory = category;
		styleIndex = index;
		lastCastingMode = castingMode;

		final StyleName style = resolve(category, index, castingMode);
		currentStyle = style == null ? CombatStyle.UNKNOWN : style.getCombatStyle();
		currentStyleName = style == null ? "Unknown" : style.getLabel();
		rapid = style == StyleName.RAPID;
	}

	void reset()
	{
		currentStyle = CombatStyle.UNKNOWN;
		currentStyleName = "Unknown";
		rapid = false;
		weaponCategory = -1;
		styleIndex = -1;
		lastCastingMode = -1;
	}

	@Nullable
	StyleName resolve(int weaponCategory, int styleIndex, int castingMode)
	{
		final StyleName[] styles = weaponTypeStyles(weaponCategory);
		if (styles == null || styles.length == 0)
		{
			return null;
		}

		// A powered staff (trident, sanguinesti, shadow, sceptres) trains Magic but its styles are
		// named Accurate/Accurate/Longrange, so the names alone would read as melee and ranged.
		// No other weapon repeats "Accurate" in the first two slots.
		if (isPoweredStaff(styles))
		{
			return StyleName.MAGIC;
		}

		// Staves put defensive casting in slot 5, selected by a separate varbit rather than by the
		// attack style varp. This mirrors the client's own script 4525.
		int index = styleIndex;
		if (index == 4)
		{
			index += castingMode;
		}

		if (index < 0 || index >= styles.length)
		{
			return null;
		}

		return styles[index];
	}

	private boolean isPoweredStaff(StyleName[] styles)
	{
		return styles.length >= 4
			&& styles[0] == StyleName.ACCURATE
			&& styles[1] == StyleName.ACCURATE
			&& styles[3] == StyleName.LONGRANGE;
	}

	@Nullable
	private StyleName[] weaponTypeStyles(int weaponCategory)
	{
		final EnumComposition categories = client.getEnum(WEAPON_STYLES_ENUM);
		if (categories == null)
		{
			return null;
		}

		final int styleEnumId = categories.getIntValue(weaponCategory);
		if (styleEnumId == -1)
		{
			switch (weaponCategory)
			{
				case CATEGORY_STAFF_FALLBACK:
					return STAFF_FALLBACK_STYLES;
				case CATEGORY_MELEE_FALLBACK:
					return MELEE_FALLBACK_STYLES;
				default:
					return null;
			}
		}

		final EnumComposition styleEnum = client.getEnum(styleEnumId);
		if (styleEnum == null)
		{
			return null;
		}

		final int[] structIds = styleEnum.getIntVals();
		final StyleName[] styles = new StyleName[structIds.length];

		int slot = 0;
		for (int structId : structIds)
		{
			final StructComposition struct = client.getStructComposition(structId);
			if (struct == null)
			{
				slot++;
				continue;
			}

			final StyleName style = StyleName.fromGameName(struct.getStringValue(STYLE_NAME_PARAM));
			if (style == null)
			{
				slot++;
				continue;
			}

			// The game reuses the "Defensive" name for the sixth slot, which is defensive casting.
			styles[slot] = slot == 5 && style == StyleName.DEFENSIVE ? StyleName.DEFENSIVE_CASTING : style;
			slot++;
		}

		return styles;
	}

	/** The style names the game stores on the attack-style structs, and what each one damages with. */
	@Getter
	enum StyleName
	{
		ACCURATE("Accurate", CombatStyle.MELEE),
		AGGRESSIVE("Aggressive", CombatStyle.MELEE),
		DEFENSIVE("Defensive", CombatStyle.MELEE),
		CONTROLLED("Controlled", CombatStyle.MELEE),
		RANGING("Accurate", CombatStyle.RANGED),
		RAPID("Rapid", CombatStyle.RANGED),
		LONGRANGE("Longrange", CombatStyle.RANGED),
		CASTING("Casting", CombatStyle.MAGIC),
		DEFENSIVE_CASTING("Defensive casting", CombatStyle.MAGIC),
		MAGIC("Magic", CombatStyle.MAGIC),
		OTHER("Other", CombatStyle.UNKNOWN),
		;

		private final String label;
		private final CombatStyle combatStyle;

		StyleName(String label, CombatStyle combatStyle)
		{
			this.label = label;
			this.combatStyle = combatStyle;
		}

		@Nullable
		static StyleName fromGameName(@Nullable String name)
		{
			if (name == null || name.isEmpty())
			{
				return null;
			}

			try
			{
				return valueOf(name.trim().toUpperCase().replace(' ', '_'));
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Unrecognised attack style name '{}'", name);
				return null;
			}
		}
	}
}
