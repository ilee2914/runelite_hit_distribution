package com.github.ilee2.hitstats;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;

/**
 * Reads the overhead prayer icons currently drawn above an NPC.
 *
 * <p>{@link net.runelite.api.HeadIcon} is only available for players. An NPC exposes its overheads
 * as parallel arrays of sprite archive ids and sprite indexes ({@link NPC#getOverheadArchiveIds()}
 * / {@link NPC#getOverheadSpriteIds()}), which is what lets a single NPC show more than one icon at
 * a time.
 */
@Slf4j
final class OverheadPrayerReader
{
	/**
	 * Sprite archive holding the overhead prayer icons. It is the archive
	 * {@code SpriteID.OVERHEAD_PROTECT_FROM_MELEE} names -- the old flat SpriteID table had one
	 * constant per archive, named after the archive's first sprite.
	 */
	private static final int PRAYER_HEADICON_ARCHIVE = 440;

	/** Some NPCs leave the archive unset, meaning "the default prayer archive". */
	private static final int DEFAULT_ARCHIVE = -1;

	private OverheadPrayerReader()
	{
	}

	/**
	 * @return every overhead prayer icon above {@code npc}, in the order the game draws them.
	 * Empty when the NPC has no overhead, or when its overheads come from an archive this plugin
	 * does not recognise (custom boss icons), which is reported as "no protection" rather than
	 * guessed at.
	 */
	static Set<OverheadPrayer> read(NPC npc)
	{
		final short[] spriteIds = npc.getOverheadSpriteIds();
		if (spriteIds == null || spriteIds.length == 0)
		{
			return Collections.emptySet();
		}

		final int[] archiveIds = npc.getOverheadArchiveIds();

		// LinkedHashSet, not EnumSet: draw order is worth keeping for the readout.
		final Set<OverheadPrayer> prayers = new LinkedHashSet<>(spriteIds.length);
		for (int i = 0; i < spriteIds.length; i++)
		{
			final int archive = archiveIds != null && i < archiveIds.length ? archiveIds[i] : DEFAULT_ARCHIVE;
			if (archive != PRAYER_HEADICON_ARCHIVE && archive != DEFAULT_ARCHIVE)
			{
				log.debug("Ignoring overhead icon from unknown archive {} on npc {}", archive, npc.getId());
				continue;
			}

			final OverheadPrayer prayer = OverheadPrayer.forSpriteIndex(spriteIds[i]);
			if (prayer == null)
			{
				log.debug("Unknown overhead sprite index {} on npc {}", spriteIds[i], npc.getId());
				continue;
			}

			prayers.add(prayer);
		}

		return prayers;
	}

	/** @return whether any of {@code prayers} protects against {@code style}. */
	static boolean blocks(Set<OverheadPrayer> prayers, CombatStyle style)
	{
		if (style == CombatStyle.UNKNOWN)
		{
			return false;
		}
		for (OverheadPrayer prayer : prayers)
		{
			if (prayer.blocks(style))
			{
				return true;
			}
		}
		return false;
	}
}
