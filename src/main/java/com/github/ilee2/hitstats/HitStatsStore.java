package com.github.ilee2.hitstats;

import com.github.ilee2.hitstats.sync.UploadBatch;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Text;

/**
 * Holds the recorded history for the logged-in player and persists it as JSON under
 * {@code ~/.runelite/hit-stats/}. All access is synchronized: the client thread writes,
 * the Swing thread reads snapshots.
 */
@Slf4j
@Singleton
public class HitStatsStore
{
	static final File HISTORY_DIR = new File(RuneLite.RUNELITE_DIR, "hit-stats");

	private static final String UNARMED = "Unarmed";
	private static final String UNKNOWN_ITEM = "Item #";

	private final Gson gson;

	/** Where history files are kept. Overridden by tests so they never touch a real profile. */
	private File directory = HISTORY_DIR;

	private HistoryData data = new HistoryData();

	/**
	 * The same records, but only those made since this character logged in. Never written to
	 * disk: a session belongs to the client it was played in, and the file already holds the
	 * lifetime totals it would be a duplicate of.
	 */
	private HistoryData session = new HistoryData();

	private long sessionStart = System.currentTimeMillis();

	/** Who the session belongs to, so a world hop continues it and a different login does not. */
	@Nullable
	private String sessionPlayer;

	/**
	 * The current fight: everything since the first attack after the last kill. Once a kill is
	 * recorded the window keeps that finished fight until the next attack starts a new one, so
	 * a kill can be read after it rather than vanishing at the moment of death.
	 */
	private HistoryData fight = new HistoryData();

	private long fightStart = System.currentTimeMillis();

	/** Set by a kill; the next attack, hit or splash starts a fresh window. */
	private boolean fightOver;

	private int lastKillNpcId = -1;
	private long lastKillMillis;

	@Nullable
	private File file;

	private boolean dirty;

	/** How many individual hits to keep. Set from config; the default matches it. */
	private int hitLogSize = 500;

	/** Bumped on every write so the panel can tell whether it has anything new to show. */
	@Getter
	private volatile long revision;

	@Getter
	@Nullable
	private String playerName;

	@Inject
	public HitStatsStore(Gson gson)
	{
		this.gson = gson;
	}

	// ----------------------------------------------------------------- lifecycle

	public synchronized void setHitLogSize(int size)
	{
		hitLogSize = Math.max(1, size);
		trimHitLog();
	}

	synchronized void setDirectory(File dir)
	{
		directory = dir;
	}

	public synchronized boolean isLoaded()
	{
		return playerName != null;
	}

	public synchronized void load(String player)
	{
		if (player.equals(playerName))
		{
			return;
		}

		unload();

		// Read before claiming to be loaded. If the read threw with the name already set, the
		// store would count as loaded with nothing in it, and the next autosave would replace
		// the player's file with that nothing.
		final File source = new File(directory, sanitise(player) + ".json");
		data = read(source);
		file = source;
		playerName = player;

		// A hop or a dropped connection unloads and reloads the same character; that is still
		// one session. Another character is not.
		if (!player.equals(sessionPlayer))
		{
			resetSession(player);
		}
		dirty = false;
		revision++;
		log.debug("Loaded hit stats for {} from {}: {} contexts, {} fights",
			player, file, data.getContexts().size(), data.getFights().size());
	}

	/**
	 * Drops the loaded file. The session is kept: logging out, hopping and losing the connection
	 * all land here, and none of them ends the session the panel is showing.
	 */
	public synchronized void unload()
	{
		save();
		playerName = null;
		file = null;
		data = new HistoryData();
		revision++;
	}

	public synchronized void save()
	{
		if (!dirty || file == null)
		{
			return;
		}

		try
		{
			write(file, data);
			dirty = false;
		}
		catch (IOException e)
		{
			log.warn("Unable to save hit stats to {}", file, e);
		}
	}

	/**
	 * Clearing the session starts its counters over and leaves the file alone. Clearing
	 * everything empties the file, and the session with it: the session is a subset of what was
	 * just thrown away, so leaving it standing would show hits that exist nowhere else.
	 */
	public synchronized void clear(HistoryScope scope)
	{
		resetSession(playerName);
		if (scope == HistoryScope.SESSION)
		{
			revision++;
			return;
		}

		data = new HistoryData();
		dirty = true;
		revision++;
		save();
	}

	private void resetSession(@Nullable String player)
	{
		session = new HistoryData();
		sessionStart = System.currentTimeMillis();
		sessionPlayer = player;
		resetFight();
		lastKillNpcId = -1;
	}

	private void resetFight()
	{
		fight = new HistoryData();
		fightStart = System.currentTimeMillis();
		fightOver = false;
	}

	private void beginFightIfOver()
	{
		if (fightOver)
		{
			resetFight();
		}
	}

	/** Every body of records a write goes into. {@link #data} is replaced on load, so not cached. */
	private HistoryData[] all()
	{
		return new HistoryData[]{data, session, fight};
	}

	/** How long the current session has been running, for the panel's status line. */
	public synchronized long getSessionMillis()
	{
		return System.currentTimeMillis() - sessionStart;
	}

	/** Whether the fight window holds a finished kill, waiting for the next attack to start over. */
	public synchronized boolean isFightOver()
	{
		return fightOver;
	}

	/** How long the current fight has run, or ran if it is over. */
	public synchronized long getFightMillis()
	{
		return (fightOver ? lastKillMillis : System.currentTimeMillis()) - fightStart;
	}

	public synchronized int getLastKillNpcId()
	{
		return lastKillNpcId;
	}

	public synchronized long getLastKillMillis()
	{
		return lastKillMillis;
	}

	// ------------------------------------------------------------------ writes

	synchronized void recordAttack(CombatContext context, int wastedTicks, int activeTicks)
	{
		beginFightIfOver();
		for (HistoryData into : all())
		{
			statsFor(into, context).recordAttack(wastedTicks, activeTicks);
		}
		touch();
	}

	synchronized void recordHit(CombatContext context, int amount, boolean max)
	{
		beginFightIfOver();
		for (HistoryData into : all())
		{
			statsFor(into, context).recordHit(amount, max);
		}
		appendHit(context, amount, max);
		touch();
	}

	synchronized void recordSplash(CombatContext context)
	{
		beginFightIfOver();
		for (HistoryData into : all())
		{
			statsFor(into, context).recordSplash();
		}
		appendHit(context, HitRecord.SPLASH, false);
		touch();
	}

	/**
	 * Moves a hit recorded a moment ago into the killing blows, now that the death has been
	 * seen. Reaches every body of records and the hit's entry in the log.
	 */
	synchronized void markKillingBlow(CombatContext context, int amount, boolean max)
	{
		for (HistoryData into : all())
		{
			final ContextStats stats = into.getContexts().get(context.getKey());
			if (stats != null)
			{
				stats.markKillingBlow(amount, max);
			}
		}

		// The same record sits in every log, so flagging it once flags it everywhere.
		for (HistoryData from : all())
		{
			final HitRecord hit = lastUnflaggedHit(from.getRecentHits(), context.getKey(), amount);
			if (hit != null)
			{
				hit.markKillingBlow();
				break;
			}
		}
		touch();
	}

	@Nullable
	private static HitRecord lastUnflaggedHit(List<HitRecord> log, String contextKey, int amount)
	{
		for (int i = log.size() - 1; i >= 0; i--)
		{
			final HitRecord hit = log.get(i);
			if (hit.getDamage() == amount && !hit.isKillingBlow() && contextKey.equals(hit.getContextKey()))
			{
				return hit;
			}
		}
		return null;
	}

	/**
	 * Reverses the most recent {@link #recordSplash} for this context: the attack it was credited
	 * to went on to produce a hitsplat, so the splash graphic was another player's.
	 */
	synchronized void undoSplash(CombatContext context)
	{
		for (HistoryData into : all())
		{
			statsFor(into, context).undoSplash();
			removeLastSplash(into.getRecentHits(), context.getKey());
		}
		touch();
	}

	private static void removeLastSplash(List<HitRecord> log, String contextKey)
	{
		for (int i = log.size() - 1; i >= 0; i--)
		{
			final HitRecord hit = log.get(i);
			if (hit.isSplash() && contextKey.equals(hit.getContextKey()))
			{
				log.remove(i);
				return;
			}
		}
	}

	private void appendHit(CombatContext context, int damage, boolean max)
	{
		// One record in every log. Nothing but the killing-blow flag changes on a HitRecord once
		// it is written, and that is meant to show everywhere, so the lists can share it.
		final HitRecord hit = new HitRecord(System.currentTimeMillis(), context.getNpcId(),
			context.getWeaponId(), damage, max, context.getKey());
		for (HistoryData into : all())
		{
			into.getRecentHits().add(hit);
		}
		trimHitLog();
	}

	private void trimHitLog()
	{
		for (HistoryData d : all())
		{
			trim(d.getRecentHits());
		}
	}

	private void trim(List<HitRecord> log)
	{
		while (log.size() > hitLogSize)
		{
			log.remove(0);
		}
	}

	synchronized void recordFight(KillRecord record)
	{
		for (HistoryData into : all())
		{
			into.getFights().add(record);
		}
		if (record.isKilled())
		{
			// The finished fight stays in the window until the next attack starts a new one.
			fightOver = true;
			lastKillNpcId = record.getNpcId();
			lastKillMillis = System.currentTimeMillis();
		}
		touch();
	}

	synchronized void recordUnattributedHit()
	{
		for (HistoryData into : all())
		{
			into.incrementUnattributed();
		}
		touch();
	}

	synchronized void rememberNpc(NPC npc)
	{
		final int id = npc.getId();
		boolean known = true;
		for (HistoryData d : all())
		{
			known &= d.getNpcNames().containsKey(id);
		}
		if (known)
		{
			return;
		}

		// The transformed composition is what the player actually sees; a base id may carry a
		// blank or generic name.
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}

		String name = composition != null ? composition.getName() : npc.getName();
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			name = "NPC #" + id;
		}
		final int level = composition != null ? composition.getCombatLevel() : npc.getCombatLevel();

		final NpcName entry = new NpcName(Text.removeTags(name), level);
		for (HistoryData into : all())
		{
			into.getNpcNames().put(id, entry);
		}
		touch();
	}

	synchronized void rememberItem(int itemId, @Nullable String name)
	{
		if (itemId < 0 || name == null)
		{
			return;
		}
		boolean known = true;
		for (HistoryData d : all())
		{
			known &= d.getItemNames().containsKey(itemId);
		}
		if (known)
		{
			return;
		}
		final String clean = Text.removeTags(name);
		for (HistoryData into : all())
		{
			into.getItemNames().put(itemId, clean);
		}
		touch();
	}

	private static ContextStats statsFor(HistoryData into, CombatContext context)
	{
		return into.getContexts().computeIfAbsent(context.getKey(), k -> new ContextStats(context));
	}

	private HistoryData dataFor(HistoryScope scope)
	{
		switch (scope)
		{
			case CURRENT_FIGHT:
				return fight;
			case SESSION:
				return session;
			default:
				return data;
		}
	}

	private void touch()
	{
		dirty = true;
		revision++;
	}

	// ------------------------------------------------------------------- reads

	public synchronized String npcName(int npcId)
	{
		final NpcName entry = npcEntry(npcId);
		return entry != null ? entry.getName() : "NPC #" + npcId;
	}

	/**
	 * Names are looked up in every body of records: logging out empties the file side while the
	 * session stays on screen, and a name learned before the session began is only in the file.
	 */
	@Nullable
	private NpcName npcEntry(int npcId)
	{
		for (HistoryData from : all())
		{
			final NpcName entry = from.getNpcNames().get(npcId);
			if (entry != null)
			{
				return entry;
			}
		}
		return null;
	}

	public synchronized String npcLabel(int npcId)
	{
		final NpcName entry = npcEntry(npcId);
		if (entry == null)
		{
			return "NPC #" + npcId;
		}
		return entry.getCombatLevel() > 0
			? entry.getName() + " (lvl " + entry.getCombatLevel() + ")"
			: entry.getName();
	}

	public synchronized String itemName(int itemId)
	{
		if (itemId < 0)
		{
			return UNARMED;
		}
		for (HistoryData from : all())
		{
			final String name = from.getItemNames().get(itemId);
			if (name != null)
			{
				return name;
			}
		}
		return UNKNOWN_ITEM + itemId;
	}

	public synchronized int getUnattributedHits(HistoryScope scope)
	{
		return dataFor(scope).getUnattributedHits();
	}

	public synchronized int getUnattributedHits()
	{
		return getUnattributedHits(HistoryScope.ALL_TIME);
	}

	public synchronized int getContextCount(HistoryScope scope)
	{
		return dataFor(scope).getContexts().size();
	}

	public synchronized int getContextCount()
	{
		return getContextCount(HistoryScope.ALL_TIME);
	}

	/**
	 * @return the individual hits matching {@code filter}, newest first. Backed by the same
	 * contexts the aggregates use, so a hit is included on exactly the same terms as its setup.
	 */
	public synchronized List<HitRecord> recentHits(HistoryFilter filter, int limit)
	{
		return recentHits(filter, limit, HistoryScope.ALL_TIME);
	}

	public synchronized List<HitRecord> recentHits(HistoryFilter filter, int limit, HistoryScope scope)
	{
		final HistoryData source = dataFor(scope);
		final List<HitRecord> log = source.getRecentHits();
		final List<HitRecord> out = new ArrayList<>();

		for (int i = log.size() - 1; i >= 0 && out.size() < limit; i--)
		{
			final HitRecord hit = log.get(i);
			final ContextStats stats = source.getContexts().get(hit.getContextKey());
			if (stats != null && matches(stats.getContext(), filter))
			{
				out.add(hit);
			}
		}

		return out;
	}

	/** @return how many attacks were made under one setup, for the hover tooltip. */
	public synchronized int attacksInContext(String key, HistoryScope scope)
	{
		final ContextStats stats = dataFor(scope).getContexts().get(key);
		return stats == null ? 0 : stats.getAttacks();
	}

	/** @return the setup a logged hit was dealt under, or null if it has since been cleared. */
	@Nullable
	public synchronized CombatContext contextFor(String key, HistoryScope scope)
	{
		final ContextStats stats = dataFor(scope).getContexts().get(key);
		return stats == null ? null : stats.getContext();
	}

	public synchronized Aggregate aggregate(HistoryFilter filter)
	{
		return aggregate(filter, HistoryScope.ALL_TIME);
	}

	public synchronized Aggregate aggregate(HistoryFilter filter, HistoryScope scope)
	{
		return aggregate(filter, scope, true);
	}

	/** @param includeKillingBlows whether hits that ended a fight are folded into the histogram. */
	public synchronized Aggregate aggregate(HistoryFilter filter, HistoryScope scope, boolean includeKillingBlows)
	{
		final HistoryData source = dataFor(scope);

		final List<ContextStats> matched = new ArrayList<>();
		for (ContextStats stats : source.getContexts().values())
		{
			if (matches(stats.getContext(), filter))
			{
				matched.add(stats);
			}
		}

		final List<KillRecord> fights = new ArrayList<>();
		for (KillRecord fight : source.getFights())
		{
			final ContextStats stats = source.getContexts().get(fight.getContextKey());
			final CombatContext context = stats != null ? stats.getContext() : null;
			if (matchesFight(fight, context, filter))
			{
				fights.add(fight);
			}
		}

		return new Aggregate(matched, fights, includeKillingBlows);
	}

	public synchronized FilterOptions options(boolean splitNpcById)
	{
		return options(splitNpcById, HistoryFilter.ALL, HistoryScope.ALL_TIME);
	}

	/**
	 * @param splitNpcById whether NPC options are one per id rather than one per name.
	 * @param base what the panel is filtering on now. Every list is built with its own dimension
	 * ignored and the others applied, so picking a weapon narrows the attack list to the attacks
	 * made with it while no list ever drops the option currently chosen in it.
	 */
	public synchronized FilterOptions options(boolean splitNpcById, HistoryFilter base)
	{
		return options(splitNpcById, base, HistoryScope.ALL_TIME);
	}

	public synchronized FilterOptions options(boolean splitNpcById, HistoryFilter base, HistoryScope scope)
	{
		final HistoryFilter forNpcs = base.withoutNpc();
		final HistoryFilter forAttacks = base.withoutAttack();

		final Map<String, int[]> npcAttacks = new HashMap<>();
		final Map<String, FilterOptions.Option> npcOptions = new HashMap<>();
		final Map<String, int[]> attackAttacks = new HashMap<>();

		// One filter per equipment slot, each ignoring only its own slot.
		final HistoryFilter[] forSlot = new HistoryFilter[CombatContext.GEAR_SLOTS];
		final List<Map<Integer, int[]>> slotAttacks = new ArrayList<>(CombatContext.GEAR_SLOTS);
		for (int slot = 0; slot < CombatContext.GEAR_SLOTS; slot++)
		{
			forSlot[slot] = base.withoutSlot(slot);
			slotAttacks.add(new HashMap<>());
		}

		for (ContextStats stats : dataFor(scope).getContexts().values())
		{
			final CombatContext context = stats.getContext();
			if (context == null)
			{
				continue;
			}
			final int weight = Math.max(1, stats.getAttacks());

			if (matches(context, forNpcs))
			{
				final String npcKey = splitNpcById
					? Integer.toString(context.getNpcId())
					: npcName(context.getNpcId());
				npcAttacks.computeIfAbsent(npcKey, k -> new int[1])[0] += weight;
				if (!npcOptions.containsKey(npcKey))
				{
					final String label = splitNpcById
						? npcLabel(context.getNpcId()) + "  #" + context.getNpcId()
						: npcLabel(context.getNpcId());
					npcOptions.put(npcKey, new FilterOptions.Option(label,
						splitNpcById ? null : npcName(context.getNpcId()),
						splitNpcById ? context.getNpcId() : null, 0));
				}
			}

			if (matches(context, forAttacks))
			{
				attackAttacks.computeIfAbsent(context.getAttackLabel(), k -> new int[1])[0] += weight;
			}

			final int[] gear = context.getGear();
			for (int slot = 0; slot < CombatContext.GEAR_SLOTS && slot < gear.length; slot++)
			{
				if (matches(context, forSlot[slot]))
				{
					slotAttacks.get(slot).computeIfAbsent(gear[slot], k -> new int[1])[0] += weight;
				}
			}
		}

		final Comparator<FilterOptions.Option> order = Comparator
			.comparingInt(FilterOptions.Option::getAttacks).reversed()
			.thenComparing(FilterOptions.Option::getLabel);

		final List<FilterOptions.Option> npcs = new ArrayList<>();
		for (Map.Entry<String, FilterOptions.Option> e : npcOptions.entrySet())
		{
			final FilterOptions.Option o = e.getValue();
			npcs.add(new FilterOptions.Option(o.getLabel(), o.getName(), o.getId(), npcAttacks.get(e.getKey())[0]));
		}
		npcs.sort(order);

		final List<FilterOptions.Option> attacks = new ArrayList<>();
		for (Map.Entry<String, int[]> e : attackAttacks.entrySet())
		{
			attacks.add(new FilterOptions.Option(e.getKey(), e.getKey(), null, e.getValue()[0]));
		}
		attacks.sort(order);

		final Map<Integer, List<FilterOptions.Option>> gearBySlot = new HashMap<>();
		for (int slot = 0; slot < CombatContext.GEAR_SLOTS; slot++)
		{
			final Map<Integer, int[]> seen = slotAttacks.get(slot);
			if (seen.isEmpty() || seen.keySet().stream().allMatch(id -> id <= 0))
			{
				// A slot nothing was ever worn in is not worth offering.
				continue;
			}

			final List<FilterOptions.Option> items = new ArrayList<>(seen.size());
			for (Map.Entry<Integer, int[]> e : seen.entrySet())
			{
				items.add(new FilterOptions.Option(slotItemName(slot, e.getKey()), null,
					e.getKey(), e.getValue()[0]));
			}
			items.sort(order);
			gearBySlot.put(slot, items);
		}

		return new FilterOptions(npcs, attacks, gearBySlot);
	}

	/** Item name for a slot, naming the empty case after the slot rather than always "Unarmed". */
	private String slotItemName(int slot, int itemId)
	{
		if (itemId > 0)
		{
			return itemName(itemId);
		}
		return slot == CombatContext.WEAPON_SLOT ? "Unarmed" : "Nothing";
	}

	private boolean matches(@Nullable CombatContext context, HistoryFilter filter)
	{
		if (context == null)
		{
			return false;
		}
		if (filter.getNpcId() != null && context.getNpcId() != filter.getNpcId())
		{
			return false;
		}
		if (filter.getNpcName() != null && !filter.getNpcName().equals(npcName(context.getNpcId())))
		{
			return false;
		}
		final int[] gear = context.getGear();
		for (Map.Entry<Integer, Integer> slot : filter.getGear().entrySet())
		{
			final int index = slot.getKey();
			if (index < 0 || index >= gear.length || gear[index] != slot.getValue())
			{
				return false;
			}
		}
		if (filter.getStyleProtected() != null && context.isStyleProtected() != filter.getStyleProtected())
		{
			return false;
		}
		return filter.getAttackLabel() == null || filter.getAttackLabel().equals(context.getAttackLabel());
	}

	private boolean matchesFight(KillRecord fight, @Nullable CombatContext context, HistoryFilter filter)
	{
		if (filter.getNpcId() != null && fight.getNpcId() != filter.getNpcId())
		{
			return false;
		}
		if (filter.getNpcName() != null && !filter.getNpcName().equals(npcName(fight.getNpcId())))
		{
			return false;
		}
		final Integer weaponId = filter.getWeaponId();
		if (weaponId != null && fight.getWeaponId() != weaponId)
		{
			return false;
		}
		// A fight record only remembers its weapon, so any other slot has to be answered by the
		// context of its opening attack.
		if (filter.getGear().size() > (weaponId != null ? 1 : 0)
			&& (context == null || !matches(context, filter)))
		{
			return false;
		}
		if (filter.getStyleProtected() != null
			&& (context == null || context.isStyleProtected() != filter.getStyleProtected()))
		{
			return false;
		}
		if (filter.getAttackLabel() == null)
		{
			return true;
		}
		return context != null && filter.getAttackLabel().equals(context.getAttackLabel());
	}

	// ------------------------------------------------------------ community upload

	/**
	 * @return the recorded setups the community server has not acknowledged yet, oldest change
	 * first and at most {@code cap} of them, or null when nothing is loaded or nothing has
	 * changed. Every record is a copy, so the caller can serialise it off the client thread while
	 * the fight carries on.
	 *
	 * <p>Counters are cumulative, not a delta. Re-sending a batch therefore changes nothing on the
	 * server, which is what makes a retry after a timeout or a crash safe.
	 */
	@Nullable
	public synchronized UploadBatch pendingUpload(int cap, String install, String client)
	{
		if (file == null || playerName == null)
		{
			return null;
		}

		final long through = data.getUploadedThrough();
		List<ContextStats> pending = new ArrayList<>();
		for (ContextStats stats : data.getContexts().values())
		{
			if (stats.getContext() != null && stats.getLastSeen() > through)
			{
				pending.add(stats.copy());
			}
		}
		if (pending.isEmpty())
		{
			return null;
		}

		pending.sort(Comparator.comparingLong(ContextStats::getLastSeen));
		if (pending.size() > cap)
		{
			pending = pending.subList(0, cap);
		}

		// Only the names the batch actually refers to; the server already knows the rest.
		final Map<Integer, NpcName> npcs = new HashMap<>();
		final Map<Integer, String> items = new HashMap<>();
		for (ContextStats stats : pending)
		{
			final CombatContext context = stats.getContext();
			final NpcName npc = data.getNpcNames().get(context.getNpcId());
			if (npc != null)
			{
				npcs.put(context.getNpcId(), npc);
			}
			for (int itemId : context.getGear())
			{
				final String name = data.getItemNames().get(itemId);
				if (name != null)
				{
					items.put(itemId, name);
				}
			}
		}

		return new UploadBatch(uploaderId(), install, client, HistoryData.CURRENT_VERSION,
			pending, npcs, items, playerName);
	}

	/**
	 * Moves the watermark up to what {@code batch} carried, so those records are not sent again.
	 *
	 * <p>The response usually arrives while the same character is still logged in. It does not on
	 * a logout upload, which is sent moments before the file is unloaded, so the watermark is
	 * written straight into that character's file instead. Without this a session shorter than the
	 * upload timer would never advance its watermark and would re-send its whole history at every
	 * login. It is never credited to a different character.
	 */
	public synchronized void markUploaded(UploadBatch batch)
	{
		final String owner = batch.getPlayer();
		if (owner == null)
		{
			return;
		}

		if (file != null && Objects.equals(playerName, owner))
		{
			if (batch.getThrough() > data.getUploadedThrough())
			{
				data.setUploadedThrough(batch.getThrough());
				dirty = true;
			}
			return;
		}

		final File target = new File(directory, sanitise(owner) + ".json");
		if (!target.isFile())
		{
			return;
		}
		try
		{
			final HistoryData stored = read(target);
			if (batch.getThrough() > stored.getUploadedThrough())
			{
				stored.setUploadedThrough(batch.getThrough());
				write(target, stored);
			}
		}
		catch (IOException | RuntimeException e)
		{
			// Losing a watermark only costs one repeated upload, which the server ignores.
			log.debug("Unable to record the upload watermark for {}", owner, e);
		}
	}

	/**
	 * @return every NPC id this file has seen under {@code name}. The panel filters by name and
	 * the server keys on id, so a monster whose phases or worlds use several ids has to be asked
	 * about by all of them. Ids other players know under that name but this file has never met
	 * are simply not asked for.
	 */
	public synchronized List<Integer> npcIdsNamed(@Nullable String name)
	{
		final List<Integer> ids = new ArrayList<>();
		if (name == null || name.isEmpty())
		{
			return ids;
		}
		for (Map.Entry<Integer, NpcName> e : data.getNpcNames().entrySet())
		{
			if (e.getValue() != null && name.equals(e.getValue().getName()))
			{
				ids.add(e.getKey());
			}
		}
		return ids;
	}

	/** @return the id this file is known to the server by, or null if it has never uploaded. */
	@Nullable
	public synchronized String getUploaderId()
	{
		return data.getUploaderId();
	}

	/** The same id, created and marked for saving if this file has never had one. */
	private String uploaderId()
	{
		String id = data.getUploaderId();
		if (id == null || id.isEmpty())
		{
			id = UUID.randomUUID().toString();
			data.setUploaderId(id);
			dirty = true;
			log.debug("Created an uploader id for {}", playerName);
		}
		return id;
	}

	// --------------------------------------------------------------------- io

	private HistoryData read(File source)
	{
		if (!source.isFile())
		{
			return new HistoryData();
		}

		try (Reader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8))
		{
			final HistoryData loaded = gson.fromJson(reader, HistoryData.class);
			if (loaded == null)
			{
				return new HistoryData();
			}
			// Contexts written by an older build might lack fields; drop anything unusable
			// rather than crash the aggregate later.
			loaded.getContexts().values().removeIf(
				s -> s == null || s.getContext() == null || s.getContext().getKey() == null || s.getCounts() == null);
			if (loaded.getVersion() != HistoryData.CURRENT_VERSION)
			{
				final int before = loaded.getContexts().size();
				log.debug("Upgrading history {} from version {} to {}", source, loaded.getVersion(),
					HistoryData.CURRENT_VERSION);
				loaded.upgrade();
				final int after = loaded.getContexts().size();
				if (after != before)
				{
					log.debug("Merged {} records written under stale keys; {} setups remain",
						before - after, after);
				}
			}
			return loaded;
		}
		catch (IOException | RuntimeException e)
		{
			// RuntimeException on purpose: Gson reports a syntax error as JsonSyntaxException but
			// a read failure mid-stream as JsonIOException, and either must move the file aside
			// rather than escape and leave the store half loaded.
			log.warn("Unable to read hit stats {}; starting a fresh one", source, e);
			final File backup = new File(source.getPath() + ".corrupt");
			if (!source.renameTo(backup))
			{
				log.warn("Unable to move corrupt history aside to {}", backup);
			}
			return new HistoryData();
		}
	}

	private void write(File target, HistoryData contents) throws IOException
	{
		final File dir = target.getParentFile();
		if (dir != null && !dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("Unable to create " + dir);
		}

		final File tmp = new File(target.getPath() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(contents, writer);
		}
		Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	static String sanitise(String player)
	{
		return player.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
	}
}
