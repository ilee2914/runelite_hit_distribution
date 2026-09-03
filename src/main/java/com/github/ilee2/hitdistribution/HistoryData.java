package com.github.ilee2.hitdistribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/** Root of the history file for one player. */
@Getter
public class HistoryData
{
	/**
	 * 1: initial format.
	 * 2: contexts carry the target's overhead prayers and whether they blocked the style used.
	 * 3: only prayers that affect the damage dealt are recorded, so protection and defence
	 * prayers no longer split a distribution.
	 * 4: Hitpoints and Prayer levels are no longer recorded, for the same reason: they drift
	 * constantly in a fight and cannot change the damage dealt.
	 * 5: a capped log of individual hits sits alongside the aggregates.
	 * 6: Defence is no longer recorded either; it cannot change the damage dealt. Older records
	 * have it dropped from their level arrays on load so the panel reads them correctly.
	 * 7: the hit that kills a monster is moved into a killing-blow histogram of its own once the
	 * death is seen, and flagged in the hit log, so the panel can chart the distribution without
	 * hits that were capped by the monster's remaining hitpoints. Records from older files keep
	 * their killing blows in the main histogram; they cannot be told apart after the fact.
	 * Contexts recorded under an older version keep their original key, so they stay separate
	 * from otherwise identical ones recorded since. Nothing is discarded.
	 */
	static final int CURRENT_VERSION = 7;

	private int version = CURRENT_VERSION;
	private Map<Integer, NpcName> npcNames = new HashMap<>();
	private Map<Integer, String> itemNames = new HashMap<>();
	private Map<String, ContextStats> contexts = new HashMap<>();
	private List<KillRecord> fights = new ArrayList<>();

	/** The most recent hits, oldest first, capped so the file cannot grow without bound. */
	private List<HitRecord> recentHits = new ArrayList<>();

	/** Own hitsplats that arrived with no attack to hang them on; a diagnostic, never charted. */
	private int unattributedHits;

	void incrementUnattributed()
	{
		unattributedHits++;
	}

	/**
	 * Brings a file read from an older version up to date. Keys are never changed, so nothing
	 * merges or disappears; only the shape of what is stored under them is adjusted.
	 */
	void upgrade()
	{
		if (version < 6)
		{
			for (ContextStats stats : contexts.values())
			{
				stats.migrate();
			}
		}
		version = CURRENT_VERSION;
	}
}
