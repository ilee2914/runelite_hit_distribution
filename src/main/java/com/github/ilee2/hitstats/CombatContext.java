package com.github.ilee2.hitstats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;

/**
 * Everything about the player and target that can move the damage distribution: worn gear,
 * levels, prayers, attack style, spell, special attack, the target NPC and the overhead prayer
 * that target was using. Two attacks made in the same context are drawn from the same
 * distribution, so hits are counted per context rather than stored one by one.
 *
 * <p>Instances are immutable; {@link #getKey()} is a stable hash of the fields and is what the
 * history file is keyed on.
 */
@Getter
public class CombatContext
{
	public static final int GEAR_SLOTS = 14;
	public static final int WEAPON_SLOT = 3;

	/** Value stored in {@link #gear} for a slot with nothing in it, and the id the gear filter
	 * uses to ask for exactly that. */
	public static final int NO_ITEM = -1;

	/**
	 * Order of the entries in {@link #boosted} and {@link #real}: the levels that can move the
	 * damage dealt. Hitpoints, Prayer and Defence are deliberately absent. The first two drift
	 * every few ticks in a fight, and keying on them turned one setup into a stream of one-attack
	 * contexts; Defence never changes the damage dealt, and an NPC that drains it split contexts
	 * for nothing.
	 */
	public static final String[] SKILL_NAMES = {"Attack", "Strength", "Ranged", "Magic"};

	/**
	 * Index of Defence in the level arrays of files written before format 6, which carried
	 * Attack, Strength, Defence, Ranged, Magic (and, before format 4, Hitpoints and Prayer after
	 * those).
	 */
	static final int LEGACY_DEFENCE_INDEX = 2;

	/** Item id per equipment slot, {@link #NO_ITEM} when empty. */
	private final int[] gear;

	/** Boosted levels, in {@link #SKILL_NAMES} order. */
	private final int[] boosted;

	/** Real (unboosted) levels, in {@link #SKILL_NAMES} order. */
	private final int[] real;

	/** Enum names of the player's active prayers, sorted. */
	private final List<String> prayers;

	private final int weaponCategory;
	private final int styleIndex;
	private final String styleName;
	private final CombatStyle combatStyle;

	/** Autocast id of the spell, -1 for a manual cast the table does not know, 0 when no spell is involved. */
	private final int spellId;

	@Nullable
	private final String spellName;

	private final boolean special;
	private final int npcId;

	/** {@link OverheadPrayer} names the target was showing when the attack was made, sorted. */
	private final List<String> targetOverheads;

	/**
	 * Whether one of {@link #targetOverheads} protects against {@link #combatStyle}. Stored rather
	 * than derived so it records what the game actually applied, even if the icon table changes.
	 */
	private final boolean styleProtected;

	/** Ticks between attacks in this context, after Rapid and spell adjustments. */
	private final int attackSpeed;

	private final String key;

	@Builder(toBuilder = true)
	public CombatContext(int[] gear, int[] boosted, int[] real, @Nullable List<String> prayers, int weaponCategory,
		int styleIndex, String styleName, CombatStyle combatStyle, int spellId, @Nullable String spellName,
		boolean special, int npcId, @Nullable List<String> targetOverheads, boolean styleProtected, int attackSpeed)
	{
		this.gear = Arrays.copyOf(gear, GEAR_SLOTS);
		this.boosted = Arrays.copyOf(boosted, SKILL_NAMES.length);
		this.real = Arrays.copyOf(real, SKILL_NAMES.length);
		this.prayers = sortedCopy(prayers);
		this.weaponCategory = weaponCategory;
		this.styleIndex = styleIndex;
		this.styleName = styleName;
		this.combatStyle = combatStyle;
		this.spellId = spellId;
		this.spellName = spellName;
		this.special = special;
		this.npcId = npcId;
		this.targetOverheads = sortedCopy(targetOverheads);
		this.styleProtected = styleProtected;
		this.attackSpeed = attackSpeed;
		this.key = computeKey();
	}

	/**
	 * Copies {@code source} with its level arrays replaced but its key kept. Only for migrating a
	 * record read from an older file: the key is what the history is stored under, and the whole
	 * point of a migration is that the record stays where it is.
	 */
	private CombatContext(CombatContext source, int[] boosted, int[] real)
	{
		this.gear = source.gear;
		this.boosted = boosted;
		this.real = real;
		this.prayers = source.prayers;
		this.weaponCategory = source.weaponCategory;
		this.styleIndex = source.styleIndex;
		this.styleName = source.styleName;
		this.combatStyle = source.combatStyle;
		this.spellId = source.spellId;
		this.spellName = source.spellName;
		this.special = source.special;
		this.npcId = source.npcId;
		this.targetOverheads = source.targetOverheads;
		this.styleProtected = source.styleProtected;
		this.attackSpeed = source.attackSpeed;
		this.key = source.key;
	}

	/**
	 * @return this context with its level arrays brought to the current {@link #SKILL_NAMES}
	 * shape, or itself if they already are. Gson fills the arrays straight from the file, so a
	 * record written when Defence was still recorded arrives with five (or seven) entries and its
	 * Ranged level sitting where the panel now expects Magic.
	 */
	CombatContext withCurrentLevelShape()
	{
		if (boosted == null || real == null
			|| (boosted.length == SKILL_NAMES.length && real.length == SKILL_NAMES.length))
		{
			return this;
		}
		return new CombatContext(this, dropLegacyDefence(boosted), dropLegacyDefence(real));
	}

	/**
	 * @return a copy of this context whose {@link #getKey() key} is computed from the fields it
	 * actually holds now. Records written before format 6 keep the key they were stored under
	 * even after {@link #withCurrentLevelShape()} has rewritten their levels, so two records
	 * that are now identical can still sit under two different keys; this is what
	 * {@code HistoryData}'s format 8 migration uses to bring them back together. It also
	 * normalises a null prayer or overhead list to an empty one, so a record from a build that
	 * predates those fields keys the same as a fresh one with neither.
	 */
	CombatContext rekeyed()
	{
		return toBuilder().build();
	}

	private static int[] dropLegacyDefence(int[] levels)
	{
		if (levels.length <= SKILL_NAMES.length)
		{
			return Arrays.copyOf(levels, SKILL_NAMES.length);
		}
		final int[] out = new int[SKILL_NAMES.length];
		int j = 0;
		for (int i = 0; i < levels.length && j < out.length; i++)
		{
			if (i != LEGACY_DEFENCE_INDEX)
			{
				out[j++] = levels[i];
			}
		}
		return out;
	}

	private static List<String> sortedCopy(@Nullable List<String> values)
	{
		if (values == null || values.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<String> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return Collections.unmodifiableList(copy);
	}

	/** @return a copy of this context with the special-attack flag set. */
	CombatContext asSpecial()
	{
		return toBuilder().special(true).build();
	}

	public int getWeaponId()
	{
		return gear[WEAPON_SLOT];
	}

	/**
	 * Null-safe. Gson bypasses the constructor, so a history file written before this field
	 * existed loads it as null rather than as an empty list; every reader has to tolerate that or
	 * the panel dies on the first upgrade.
	 */
	public List<String> getPrayers()
	{
		return prayers == null ? Collections.emptyList() : prayers;
	}

	/** Null-safe for the same reason as {@link #getPrayers()}. */
	public List<String> getTargetOverheads()
	{
		return targetOverheads == null ? Collections.emptyList() : targetOverheads;
	}

	/**
	 * What the attack was: the spell if one was cast, otherwise the attack style the combat tab
	 * was set to (Accurate, Aggressive, Rapid, Longrange, Casting). The damage type the game
	 * shows beside that -- Stab, Slash, Crush -- is not exposed by the client API, so it cannot
	 * be recorded without a hardcoded weapon table that would go stale.
	 */
	public String getAttackLabel()
	{
		if (spellName != null)
		{
			return spellName;
		}
		if (styleName != null && !styleName.isEmpty() && !"Unknown".equals(styleName))
		{
			return styleName;
		}
		return combatStyle.getLabel();
	}

	/** @return the target's overheads as display names, or "No overhead" when it had none. */
	public String getTargetPrayerLabel()
	{
		final List<String> overheads = getTargetOverheads();
		if (overheads.isEmpty())
		{
			return "No overhead";
		}
		final List<String> labels = new ArrayList<>(overheads.size());
		for (String name : overheads)
		{
			labels.add(OverheadPrayer.labelFor(name));
		}
		return String.join(", ", labels);
	}

	private String computeKey()
	{
		final StringBuilder sb = new StringBuilder(256);
		sb.append("g").append(Arrays.toString(gear));
		sb.append("b").append(Arrays.toString(boosted));
		sb.append("r").append(Arrays.toString(real));
		sb.append("p").append(prayers);
		sb.append("c").append(weaponCategory).append('/').append(styleIndex).append('/').append(styleName);
		sb.append("s").append(combatStyle).append('/').append(spellId).append('/').append(spellName);
		sb.append("x").append(special);
		sb.append("n").append(npcId);
		sb.append("o").append(targetOverheads).append('/').append(styleProtected);
		sb.append("a").append(attackSpeed);

		try
		{
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder(32);
			for (int i = 0; i < 16; i++)
			{
				hex.append(String.format("%02x", hash[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-256 is mandatory in every JRE; fall back to something stable anyway.
			return Integer.toHexString(sb.toString().hashCode());
		}
	}
}
