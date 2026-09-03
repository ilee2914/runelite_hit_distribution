package com.github.ilee2.hitdistribution.sync;

import com.github.ilee2.hitdistribution.CombatContext;
import com.github.ilee2.hitdistribution.HistoryFilter;
import com.github.ilee2.hitdistribution.LevelMatch;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * One community lookup, as a URL. The parameters are the panel's own filter and nothing else, so
 * the community line and the player's line describe the same kind of setup; a server-side
 * dimension the panel cannot filter on would silently compare two different populations.
 *
 * <p>A weapon is required. "Every weapon at this monster" is not a distribution anyone can read,
 * and it is also the one shape broad enough to make the server work hard.
 */
public class CommunityQuery
{
	static final String PATH = "/v1/distribution";

	private final List<Integer> npcIds;
	private final Map<Integer, Integer> gear;

	@Nullable
	private final String attackLabel;

	@Nullable
	private final Boolean styleProtected;

	private final LevelMatch levels;

	/** Real level of the skill that drives this style's damage, or -1 when it is not known. */
	private final int anchorLevel;

	@Nullable
	private final int[] realLevels;

	@Nullable
	private final String me;

	public CommunityQuery(HistoryFilter filter, List<Integer> npcIds, LevelMatch levels,
		int anchorLevel, @Nullable int[] realLevels, @Nullable String me)
	{
		this.npcIds = new ArrayList<>(npcIds);
		Collections.sort(this.npcIds);
		this.gear = filter.getGear();
		this.attackLabel = filter.getAttackLabel();
		this.styleProtected = filter.getStyleProtected();
		this.levels = levels;
		this.anchorLevel = anchorLevel;
		this.realLevels = realLevels;
		this.me = me;
	}

	/**
	 * @return whether this is worth asking about: a monster, and either a weapon or a fully
	 * specified set of worn gear.
	 */
	public boolean isAskable()
	{
		return !npcIds.isEmpty()
			&& (gear.containsKey(CombatContext.WEAPON_SLOT) || gear.size() == CombatContext.GEAR_SLOTS);
	}

	public String toUrl(String baseUrl)
	{
		final StringBuilder sb = new StringBuilder(baseUrl).append(PATH).append("?npc=");
		for (int i = 0; i < npcIds.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append(npcIds.get(i));
		}

		for (int slot = 0; slot < CombatContext.GEAR_SLOTS; slot++)
		{
			final Integer item = gear.get(slot);
			if (item != null)
			{
				sb.append("&s").append(slot).append('=').append(item);
			}
		}

		if (attackLabel != null && !attackLabel.isEmpty())
		{
			sb.append("&attack=").append(encode(attackLabel));
		}

		sb.append("&protected=");
		sb.append(styleProtected == null ? "any" : (styleProtected ? "1" : "0"));

		if (levels == LevelMatch.BRACKET && anchorLevel > 0)
		{
			sb.append("&levels=bracket&level=").append(anchorLevel);
		}
		else if (levels == LevelMatch.EXACT && realLevels != null
			&& realLevels.length == CombatContext.SKILL_NAMES.length)
		{
			sb.append("&levels=exact")
				.append("&rAtt=").append(realLevels[0])
				.append("&rStr=").append(realLevels[1])
				.append("&rRng=").append(realLevels[2])
				.append("&rMag=").append(realLevels[3]);
		}
		else
		{
			sb.append("&levels=any");
		}

		sb.append("&epoch=current");

		if (me != null && !me.isEmpty())
		{
			sb.append("&me=").append(encode(me));
		}
		return sb.toString();
	}

	/** Stable identity of this lookup, for the answer cache. */
	public String cacheKey()
	{
		return toUrl("");
	}

	private static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			// UTF-8 is required of every JVM.
			throw new IllegalStateException(e);
		}
	}
}
