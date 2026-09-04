package com.github.ilee2.hitstats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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
	 * 8: every record's key is recomputed from the fields it actually holds, and records that
	 * land on the same key are merged. Versions 3, 4 and 6 each removed something from the key
	 * but left existing records under the key they were written with, so a setup recorded before
	 * one of those changes sat in its own frozen record and never received another hit. One real
	 * file held 983 records that were 140 setups. See {@link #mergeStaleKeys()}.
	 */
	public static final int CURRENT_VERSION = 8;

	/** The first version whose keys are computed the way {@link CombatContext} computes them now. */
	private static final int FIRST_STABLE_KEY_VERSION = 8;

	private int version = CURRENT_VERSION;
	private Map<Integer, NpcName> npcNames = new HashMap<>();
	private Map<Integer, String> itemNames = new HashMap<>();
	private Map<String, ContextStats> contexts = new HashMap<>();
	private List<KillRecord> fights = new ArrayList<>();

	/** The most recent hits, oldest first, capped so the file cannot grow without bound. */
	private List<HitRecord> recentHits = new ArrayList<>();

	/** Own hitsplats that arrived with no attack to hang them on; a diagnostic, never charted. */
	private int unattributedHits;

	/**
	 * Random id identifying this file to the community server, created the first time an upload
	 * is attempted and never derived from anything about the account. Null in every file written
	 * before the upload existed, and in one that has never opted in. Clearing the history throws
	 * the whole {@code HistoryData} away, so a cleared history becomes a new uploader and the
	 * rows already on the server are left alone; that is the right behaviour for counters that
	 * have just gone back to zero.
	 */
	@Nullable
	private String uploaderId;

	/**
	 * The largest {@link ContextStats#getLastSeen()} the server has acknowledged. Everything
	 * touched since is what the next upload sends.
	 */
	private long uploadedThrough;

	void setUploaderId(String id)
	{
		uploaderId = id;
	}

	void setUploadedThrough(long through)
	{
		uploadedThrough = through;
	}

	void incrementUnattributed()
	{
		unattributedHits++;
	}

	/**
	 * Brings a file read from an older version up to date. Nothing is ever discarded: a record
	 * either stays where it is or is added into the record it turns out to be a duplicate of.
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
		if (version < FIRST_STABLE_KEY_VERSION)
		{
			mergeStaleKeys();
		}
		version = CURRENT_VERSION;
	}

	/**
	 * Recomputes every record's key from the fields it holds and folds together the records that
	 * agree. Two records can only collide here if every field in the key matches, which means
	 * they describe the same setup and their counters are the same measurement taken twice; every
	 * counter is additive, so merging them loses nothing.
	 *
	 * <p>Fights and logged hits point at a context by key, so they are moved with the records
	 * they belong to. Missing that leaves them pointing at keys nothing answers to.
	 */
	private void mergeStaleKeys()
	{
		final Map<String, ContextStats> merged = new HashMap<>(contexts.size());
		final Map<String, String> moved = new HashMap<>();

		for (Map.Entry<String, ContextStats> entry : contexts.entrySet())
		{
			final ContextStats stats = entry.getValue();
			if (stats == null || stats.getContext() == null)
			{
				continue;
			}

			final CombatContext rekeyed = stats.getContext().rekeyed();
			final String key = rekeyed.getKey();
			if (!key.equals(entry.getKey()))
			{
				moved.put(entry.getKey(), key);
			}

			final ContextStats existing = merged.get(key);
			if (existing == null)
			{
				stats.rekey(rekeyed);
				merged.put(key, stats);
			}
			else
			{
				existing.mergeFrom(stats);
			}
		}

		contexts = merged;

		if (moved.isEmpty())
		{
			return;
		}
		for (KillRecord fight : fights)
		{
			fight.remapContext(moved);
		}
		for (HitRecord hit : recentHits)
		{
			hit.remapContext(moved);
		}
	}
}
