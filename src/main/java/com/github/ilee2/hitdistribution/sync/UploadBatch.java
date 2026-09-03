package com.github.ilee2.hitdistribution.sync;

import com.github.ilee2.hitdistribution.CombatContext;
import com.github.ilee2.hitdistribution.ContextStats;
import com.github.ilee2.hitdistribution.NpcName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * One upload, exactly as it goes over the wire. Every record carries its cumulative counters
 * rather than what changed since last time, so re-sending a batch cannot double count anything:
 * the server replaces a record instead of adding to it. That is what makes a retry after a
 * timeout, a crash between the response and the next save, and an out-of-order arrival all
 * harmless.
 *
 * <p>Field names are the wire format. Renaming one is a payload change and needs {@link #v}
 * bumped and the server taught the new shape.
 */
@Getter
public class UploadBatch
{
	/** Payload version. The server accepts exactly one; see the plan's section 8.5. */
	private final int v = 1;

	private final String uploader;
	private final String install;
	private final String client;

	/**
	 * The history file format that produced these keys. The key is a hash of the context's
	 * fields, so a future format that changes what goes into it produces different keys for the
	 * same setup; the server needs to be able to tell those generations apart.
	 */
	private final int keyVersion;

	private final long sentAt;
	private final List<Context> contexts;
	private final Map<Integer, NpcName> npcNames;
	private final Map<Integer, String> itemNames;

	/** Not serialised: who the batch was built for, so a late response cannot credit another login. */
	private final transient String player;

	/** Not serialised: the watermark to store when the server accepts this batch. */
	private final transient long through;

	public UploadBatch(String uploader, String install, String client, int keyVersion,
		List<ContextStats> records, Map<Integer, NpcName> npcNames, Map<Integer, String> itemNames,
		String player)
	{
		this.uploader = uploader;
		this.install = install;
		this.client = client;
		this.keyVersion = keyVersion;
		this.sentAt = System.currentTimeMillis();
		this.npcNames = npcNames;
		this.itemNames = itemNames;
		this.player = player;

		this.contexts = new ArrayList<>(records.size());
		long latest = 0;
		for (ContextStats stats : records)
		{
			contexts.add(new Context(stats));
			latest = Math.max(latest, stats.getLastSeen());
		}
		this.through = latest;
	}

	public int size()
	{
		return contexts.size();
	}

	/** One recorded setup and everything counted under it. */
	@Getter
	public static class Context
	{
		private final String key;
		private final CombatContext ctx;
		private final int[] counts;

		@Nullable
		private final int[] killCounts;

		private final int attacks;
		private final int hitsplats;
		private final int splashes;
		private final int maxHits;
		private final int killingBlows;
		private final int killingBlowMaxHits;
		private final int wastedTicks;
		private final int activeTicks;
		private final long firstSeen;
		private final long lastSeen;

		Context(ContextStats stats)
		{
			this.key = stats.getContext().getKey();
			this.ctx = stats.getContext();
			this.counts = stats.getCounts();
			this.killCounts = stats.getKillCounts().length == 0 ? null : stats.getKillCounts();
			this.attacks = stats.getAttacks();
			this.hitsplats = stats.getHitsplats();
			this.splashes = stats.getSplashes();
			this.maxHits = stats.getMaxHits();
			this.killingBlows = stats.getKillingBlows();
			this.killingBlowMaxHits = stats.getKillingBlowMaxHits();
			this.wastedTicks = stats.getWastedTicks();
			this.activeTicks = stats.getActiveTicks();
			this.firstSeen = stats.getFirstSeen();
			this.lastSeen = stats.getLastSeen();
		}
	}
}
