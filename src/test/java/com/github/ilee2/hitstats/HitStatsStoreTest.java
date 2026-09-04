package com.github.ilee2.hitstats;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HitStatsStoreTest
{
	private static final double EPS = 1e-9;

	/** History files are written under here, never under the real RuneLite directory. */
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** EquipmentInventorySlot.BODY, spelled out so the test reads without the enum. */
	private static final int BODY_SLOT = 4;

	@Test
	public void aggregateFoldsMatchingContexts()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext whipSpec = whip.asSpecial();
		final CombatContext bow = context(11235, CombatStyle.RANGED, 6, 100);
		final CombatContext other = context(4151, CombatStyle.MELEE, 4, 200);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);
		store.recordAttack(whip, 1, 5);
		store.recordHit(whip, 0, false);
		store.recordAttack(whipSpec, 0, 4);
		store.recordHit(whipSpec, 20, true);
		store.recordAttack(bow, 0, 6);
		store.recordHit(bow, 30, false);
		store.recordAttack(other, 0, 4);
		store.recordHit(other, 5, false);

		final Aggregate all = store.aggregate(HistoryFilter.ALL);
		assertEquals(5, all.getAttacks());
		assertEquals(65, all.getTotalDamage());
		assertEquals(4, all.getContexts().size());

		final Aggregate whipOnly = store.aggregate(new HistoryFilter(null, null, weapon(4151), null, null));
		assertEquals(4, whipOnly.getAttacks());
		assertEquals(35, whipOnly.getTotalDamage());
		assertEquals(1, whipOnly.getZeroHits());
		assertEquals(3, whipOnly.getLandedHits());
		assertEquals(0.75, whipOnly.getAccuracy(), EPS);
		assertEquals(35.0 / 4, whipOnly.getAveragePerAttack(), EPS);
		assertEquals(35.0 / 3, whipOnly.getAveragePerLandedHit(), EPS);
		assertEquals(20, whipOnly.getHighestHit());
		assertEquals(1, whipOnly.getMaxHits());
		assertEquals(1, whipOnly.getWastedTicks());
		assertEquals(17, whipOnly.getActiveTicks());
		assertEquals(35 / (17 * 0.6), whipOnly.getDps(), EPS);

		final Aggregate npc200 = store.aggregate(new HistoryFilter("NPC #200", null, null, null, null));
		assertEquals(1, npc200.getAttacks());
		assertEquals(5, npc200.getTotalDamage());

		final Aggregate ranged = store.aggregate(new HistoryFilter(null, null, null, "Rapid", null));
		assertEquals(1, ranged.getAttacks());
		assertArrayEquals(new int[31], zeroExcept(ranged.getCounts(), 30));
	}

	@Test
	public void splashesCountAgainstAccuracyAndSplashRate()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext barrage = AttackMatcherTest.builder(CombatStyle.MAGIC, 5, 100)
			.styleIndex(4)
			.styleName("Casting")
			.spellId(46)
			.spellName("Ice Barrage")
			.build();

		store.recordAttack(barrage, 0, 5);
		store.recordHit(barrage, 25, false);
		store.recordAttack(barrage, 0, 5);
		store.recordSplash(barrage);
		store.recordAttack(barrage, 0, 5);
		store.recordHit(barrage, 0, false);
		store.recordAttack(barrage, 0, 5);
		store.recordSplash(barrage);

		final Aggregate a = store.aggregate(new HistoryFilter(null, null, null, "Ice Barrage", null));
		assertEquals(4, a.getAttacks());
		assertEquals(4, a.getAttempts());
		assertEquals(2, a.getSplashes());
		assertEquals(0.5, a.getSplashRate(), EPS);
		assertEquals(0.25, a.getAccuracy(), EPS);
		assertEquals(25.0 / 4, a.getAveragePerAttack(), EPS);
		assertEquals(4, a.getMagicAttacks());
	}

	@Test
	public void splashRateIgnoresMeleeAndRangedHitsplats()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext barrage = AttackMatcherTest.builder(CombatStyle.MAGIC, 5, 100)
			.styleIndex(4)
			.styleName("Casting")
			.spellId(46)
			.spellName("Ice Barrage")
			.build();
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(barrage, 0, 5);
		store.recordHit(barrage, 25, false);
		store.recordAttack(barrage, 0, 5);
		store.recordSplash(barrage);
		for (int i = 0; i < 6; i++)
		{
			store.recordAttack(whip, 0, 4);
			store.recordHit(whip, 10, false);
		}

		// One hit and one splash with magic: half the magic attempts splashed, however much
		// melee sits in the same filter.
		final Aggregate all = store.aggregate(HistoryFilter.ALL);
		assertEquals(8, all.getAttempts());
		assertEquals(2, all.getMagicHitsplats() + all.getSplashes());
		assertEquals(0.5, all.getSplashRate(), EPS);

		final Aggregate meleeOnly = store.aggregate(new HistoryFilter(null, null, null, "Aggressive", null));
		assertEquals(6, meleeOnly.getHitsplats());
		assertEquals(0, meleeOnly.getSplashRate(), EPS);
	}

	@Test
	public void undoSplashTakesBackTheCountAndTheLogEntry()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext barrage = AttackMatcherTest.builder(CombatStyle.MAGIC, 5, 100)
			.styleName("Casting")
			.spellId(46)
			.spellName("Ice Barrage")
			.build();

		store.recordAttack(barrage, 0, 5);
		store.recordHit(barrage, 20, false);
		store.recordAttack(barrage, 0, 5);
		store.recordSplash(barrage);
		store.undoSplash(barrage);
		store.recordHit(barrage, 30, false);

		final Aggregate a = store.aggregate(HistoryFilter.ALL);
		assertEquals(0, a.getSplashes());
		assertEquals(2, a.getHitsplats());
		assertEquals(50, a.getTotalDamage());

		final List<HitRecord> hits = store.recentHits(HistoryFilter.ALL, 10);
		assertEquals(2, hits.size());
		assertFalse(hits.get(0).isSplash());
		assertFalse(hits.get(1).isSplash());
		assertEquals(30, hits.get(0).getDamage());

		// Nothing to take back is not an error and does not go negative.
		store.undoSplash(barrage);
		assertEquals(0, store.aggregate(HistoryFilter.ALL).getSplashes());
	}

	@Test
	public void upgradeDropsDefenceFromOlderLevelArrays()
	{
		final Gson gson = new Gson();
		final String json = "{\"version\":5,\"npcNames\":{},\"itemNames\":{},\"fights\":[],\"recentHits\":[],"
			+ "\"contexts\":{"
			+ "\"k5\":{\"context\":{\"gear\":[-1,-1,-1,4151,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1],"
			+ "\"boosted\":[118,112,99,90,80],\"real\":[99,99,99,90,80],\"prayers\":[],"
			+ "\"weaponCategory\":1,\"styleIndex\":1,\"styleName\":\"Aggressive\",\"combatStyle\":\"MELEE\","
			+ "\"spellId\":0,\"special\":false,\"npcId\":100,\"targetOverheads\":[],\"styleProtected\":false,"
			+ "\"attackSpeed\":4,\"key\":\"k5\"},\"counts\":[1,0,3],\"attacks\":4,\"hitsplats\":4},"
			+ "\"k7\":{\"context\":{\"gear\":[-1,-1,-1,4151,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1],"
			+ "\"boosted\":[1,2,3,4,5,6,7],\"real\":[1,2,3,4,5,6,7],\"prayers\":[],"
			+ "\"weaponCategory\":1,\"styleIndex\":1,\"styleName\":\"Aggressive\",\"combatStyle\":\"MELEE\","
			+ "\"spellId\":0,\"special\":false,\"npcId\":100,\"targetOverheads\":[],\"styleProtected\":false,"
			+ "\"attackSpeed\":4,\"key\":\"k7\"},\"counts\":[0,1],\"attacks\":1,\"hitsplats\":1}"
			+ "}}";

		final HistoryData data = gson.fromJson(json, HistoryData.class);
		assertEquals(5, data.getVersion());
		data.upgrade();
		assertEquals(HistoryData.CURRENT_VERSION, data.getVersion());

		// The two records describe different levels, so they stay two records. Their keys are
		// recomputed from the migrated fields (format 8), so neither answers to the name it was
		// stored under any more.
		assertEquals(2, data.getContexts().size());
		assertNull(data.getContexts().get("k5"));
		assertNull(data.getContexts().get("k7"));

		// Format 5: Attack, Strength, Defence, Ranged, Magic. Defence goes, order is kept.
		final CombatContext five = contextWithAttacks(data, 4);
		assertArrayEquals(new int[]{118, 112, 90, 80}, five.getBoosted());
		assertArrayEquals(new int[]{99, 99, 90, 80}, five.getReal());
		assertEquals(4151, five.getWeaponId());
		assertEquals(4, five.getAttackSpeed());

		// Before format 4 the arrays ran on to Hitpoints and Prayer; those fall off the end.
		final CombatContext seven = contextWithAttacks(data, 1);
		assertArrayEquals(new int[]{1, 2, 4, 5}, seven.getBoosted());

		// Every record is stored under the key its own fields produce.
		for (Map.Entry<String, ContextStats> e : data.getContexts().entrySet())
		{
			assertEquals(e.getKey(), e.getValue().getContext().getKey());
		}

		// Already current: untouched.
		final CombatContext current = context(4151, CombatStyle.MELEE, 4, 100);
		assertEquals(CombatContext.SKILL_NAMES.length, current.getBoosted().length);
		assertSame(current, current.withCurrentLevelShape());
	}

	@Test
	public void upgradeMergesRecordsLeftUnderStaleKeys()
	{
		// Three records with identical fields under three different keys: what versions 3, 4 and 6
		// left behind every time they removed something from the key.
		final String json = "{\"version\":7,\"npcNames\":{},\"itemNames\":{},"
			+ "\"fights\":[" + fightJson("b") + "],"
			+ "\"recentHits\":[" + hitJson("c", 7) + "],"
			+ "\"contexts\":{"
			+ "\"a\":" + staleStatsJson("a", "[2,0,1]", "[0,0,0,1]", 4, 4, 1, 100, 200)
			+ ",\"b\":" + staleStatsJson("b", "[0,0,3]", "null", 3, 3, 0, 150, 250)
			+ ",\"c\":" + staleStatsJson("c", "[1]", "null", 1, 1, 2, 50, 300)
			+ "}}";

		final HistoryData data = new Gson().fromJson(json, HistoryData.class);
		assertEquals(3, data.getContexts().size());
		data.upgrade();

		assertEquals(HistoryData.CURRENT_VERSION, data.getVersion());
		assertEquals(1, data.getContexts().size());

		final Map.Entry<String, ContextStats> only = data.getContexts().entrySet().iterator().next();
		final ContextStats stats = only.getValue();
		assertEquals(only.getKey(), stats.getContext().getKey());
		assertNotEquals("a", only.getKey());

		// Counters add up and histograms sum index by index, at the length of the longest.
		assertArrayEquals(new int[]{3, 0, 4}, stats.getCounts());
		assertArrayEquals(new int[]{0, 0, 0, 1}, stats.getKillCounts());
		assertEquals(8, stats.getAttacks());
		assertEquals(8, stats.getHitsplats());
		assertEquals(3, stats.getSplashes());
		assertEquals(50, stats.getFirstSeen());
		assertEquals(300, stats.getLastSeen());

		// The fight and the logged hit followed their record to its new key.
		assertEquals(only.getKey(), data.getFights().get(0).getContextKey());
		assertEquals(only.getKey(), data.getRecentHits().get(0).getContextKey());
	}

	@Test
	public void loadMergesStaleKeysAndKeepsTheHitLogPointingAtThem()
	{
		final String json = "{\"version\":7,\"npcNames\":{},\"itemNames\":{},"
			+ "\"fights\":[" + fightJson("b") + "],"
			+ "\"recentHits\":[" + hitJson("b", 2) + "," + hitJson("a", 2) + "],"
			+ "\"contexts\":{"
			+ "\"a\":" + staleStatsJson("a", "[0,0,2]", "null", 2, 2, 0, 100, 200)
			+ ",\"b\":" + staleStatsJson("b", "[0,0,1]", "null", 1, 1, 0, 150, 250)
			+ "}}";

		writeHistory("Carol", json);

		final HitStatsStore store = new HitStatsStore(new Gson());
		store.setDirectory(folder.getRoot());
		store.load("Carol");

		assertEquals(1, store.getContextCount());

		final Aggregate all = store.aggregate(HistoryFilter.ALL);
		assertEquals(3, all.getAttacks());
		assertEquals(3, all.getHitsplats());
		assertEquals(6, all.getTotalDamage());

		// Both logged hits still resolve to a context, so both are still listed. Before the merge
		// one of them pointed at a key nothing answered to and vanished from the panel.
		assertEquals(2, store.recentHits(HistoryFilter.ALL, 10).size());

		// The merge is written back on the next save, at the current version.
		store.recordHit(store.contextFor(store.recentHits(HistoryFilter.ALL, 1).get(0).getContextKey(),
			HistoryScope.ALL_TIME), 5, false);
		store.save();
		store.unload();
		store.load("Carol");
		assertEquals(1, store.getContextCount());
		assertEquals(4, store.aggregate(HistoryFilter.ALL).getHitsplats());
	}

	/** One {@code ContextStats} of the fixed setup used by the merge tests, under {@code key}. */
	private static String staleStatsJson(String key, String counts, String killCounts, int attacks,
		int hitsplats, int splashes, long firstSeen, long lastSeen)
	{
		return "{\"context\":{\"gear\":[-1,-1,-1,4151,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1],"
			+ "\"boosted\":[99,99,1,1],\"real\":[99,99,1,1],\"prayers\":[\"PIETY\"],"
			+ "\"weaponCategory\":22,\"styleIndex\":1,\"styleName\":\"Aggressive\","
			+ "\"combatStyle\":\"MELEE\",\"spellId\":0,\"special\":false,\"npcId\":9036,"
			+ "\"targetOverheads\":[],\"styleProtected\":false,\"attackSpeed\":4,"
			+ "\"key\":\"" + key + "\"},"
			+ "\"counts\":" + counts
			+ (killCounts.equals("null") ? "" : ",\"killCounts\":" + killCounts)
			+ ",\"attacks\":" + attacks + ",\"hitsplats\":" + hitsplats + ",\"splashes\":" + splashes
			+ ",\"firstSeen\":" + firstSeen + ",\"lastSeen\":" + lastSeen + "}";
	}

	private static String fightJson(String contextKey)
	{
		return "{\"timestamp\":1,\"npcId\":9036,\"contextKey\":\"" + contextKey + "\",\"weaponId\":4151,"
			+ "\"durationTicks\":10,\"attacks\":2,\"hitsplats\":2,\"damage\":4,\"misses\":0,"
			+ "\"splashes\":0,\"wastedTicks\":0,\"killed\":true}";
	}

	private static String hitJson(String contextKey, int damage)
	{
		return "{\"timestamp\":2,\"npcId\":9036,\"weaponId\":4151,\"damage\":" + damage
			+ ",\"max\":false,\"contextKey\":\"" + contextKey + "\"}";
	}

	private void writeHistory(String player, String json)
	{
		try
		{
			Files.write(new File(folder.getRoot(), player + ".json").toPath(),
				json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new AssertionError("Unable to write the test history file", e);
		}
	}

	/** @return the one context in {@code data} whose record holds {@code attacks} attacks. */
	private static CombatContext contextWithAttacks(HistoryData data, int attacks)
	{
		CombatContext found = null;
		for (ContextStats stats : data.getContexts().values())
		{
			if (stats.getAttacks() == attacks)
			{
				assertNull("more than one record holds " + attacks + " attacks", found);
				found = stats.getContext();
			}
		}
		assertNotNull("no record holds " + attacks + " attacks", found);
		return found;
	}

	@Test
	public void targetPrayerSplitsContextsAndFilters()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext open = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext prayed = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Collections.singletonList("PROTECT_FROM_MELEE"))
			.styleProtected(true)
			.build();

		// Identical gear and stats, but the target was praying: a separate distribution.
		assertNotEquals(open.getKey(), prayed.getKey());

		store.recordAttack(open, 0, 4);
		store.recordHit(open, 20, false);
		store.recordAttack(prayed, 0, 4);
		store.recordHit(prayed, 0, false);
		store.recordAttack(prayed, 0, 4);
		store.recordHit(prayed, 1, false);

		final Aggregate all = store.aggregate(HistoryFilter.ALL);
		assertEquals(3, all.getAttacks());
		assertEquals(2, all.getProtectedAttacks());
		assertEquals(2.0 / 3, all.getProtectedShare(), EPS);
		assertEquals(21.0 / 3, all.getAveragePerAttack(), EPS);

		// The number the player actually wants: what the gear does when it is not being prayed off.
		final Aggregate unprotected = store.aggregate(new HistoryFilter(null, null, null, null, Boolean.FALSE));
		assertEquals(1, unprotected.getAttacks());
		assertEquals(20.0, unprotected.getAveragePerAttack(), EPS);
		assertEquals(0, unprotected.getProtectedAttacks());

		final Aggregate protectedOnly = store.aggregate(new HistoryFilter(null, null, null, null, Boolean.TRUE));
		assertEquals(2, protectedOnly.getAttacks());
		assertEquals(1, protectedOnly.getTotalDamage());
		assertEquals(2, protectedOnly.getProtectedAttacks());
	}

	@Test
	public void fightsFilterAndSummarise()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);

		store.recordFight(new KillRecord(1L, 100, whip.getKey(), 4151, 20, 5, 5, 50, 1, 0, 2, true));
		store.recordFight(new KillRecord(2L, 100, whip.getKey(), 4151, 10, 2, 2, 12, 0, 0, 0, false));
		store.recordFight(new KillRecord(3L, 200, "missing", 4151, 30, 8, 8, 70, 2, 0, 1, true));

		final Aggregate all = store.aggregate(HistoryFilter.ALL);
		assertEquals(3, all.getFights());
		assertEquals(2, all.getKills());
		assertEquals(25 * 0.6, all.getAverageKillSeconds(), EPS);

		final Aggregate npc100 = store.aggregate(new HistoryFilter("NPC #100", null, null, null, null));
		assertEquals(2, npc100.getFights());
		assertEquals(1, npc100.getKills());
		assertEquals(20 * 0.6, npc100.getAverageKillSeconds(), EPS);

		// A fight whose context is unknown still matches by NPC and weapon, but not by attack label.
		final Aggregate melee = store.aggregate(new HistoryFilter(null, null, null, "Aggressive", null));
		assertEquals(2, melee.getFights());
	}

	@Test
	public void anySlotCanBeFilteredOnNotJustTheWeapon()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final int torva = 26382;
		final int bandos = 11832;

		final CombatContext inTorva = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.gear(gearWith(4151, BODY_SLOT, torva))
			.build();
		final CombatContext inBandos = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.gear(gearWith(4151, BODY_SLOT, bandos))
			.build();

		store.recordAttack(inTorva, 0, 4);
		store.recordHit(inTorva, 30, false);
		store.recordAttack(inBandos, 0, 4);
		store.recordHit(inBandos, 10, false);

		// Same weapon, same monster, different body: two distributions worth telling apart.
		final Map<Integer, Integer> body = new HashMap<>();
		body.put(BODY_SLOT, torva);
		final Aggregate torvaOnly = store.aggregate(new HistoryFilter(null, null, body, null, null));
		assertEquals(1, torvaOnly.getAttacks());
		assertEquals(30, torvaOnly.getTotalDamage());

		// The body slot offers both, and the weapon slot still offers the one weapon used.
		final FilterOptions options = store.options(false, HistoryFilter.ALL);
		assertEquals(2, options.getGearBySlot().get(BODY_SLOT).size());
		assertEquals(1, options.getWeapons().size());

		// With a body chosen, the weapon list narrows to what was worn alongside it.
		final FilterOptions withBody = store.options(false, new HistoryFilter(null, null, body, null, null));
		assertEquals(1, withBody.getWeapons().size());
		assertEquals(2, withBody.getGearBySlot().get(BODY_SLOT).size());
	}

	@Test
	public void anEmptySlotIsAChoiceOfItsOwn()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final int torva = 26384;
		final CombatContext inTorva = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.gear(gearWith(4151, BODY_SLOT, torva))
			.build();
		final CombatContext bare = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(inTorva, 0, 4);
		store.recordHit(inTorva, 30, false);
		store.recordAttack(bare, 0, 4);
		store.recordHit(bare, 10, false);

		// Having worn nothing in a slot is a setup like any other, so it is offered alongside the
		// items and can be filtered on. Without it, "no body" is only reachable as "all bodies".
		final List<FilterOptions.Option> bodies = store.options(false, HistoryFilter.ALL)
			.getGearBySlot().get(BODY_SLOT);
		assertEquals(2, bodies.size());
		assertTrue(bodies.stream().anyMatch(o -> o.getId() != null && o.getId() == CombatContext.NO_ITEM));

		final Aggregate nothing = store.aggregate(new HistoryFilter(null, null,
			Collections.singletonMap(BODY_SLOT, CombatContext.NO_ITEM), null, null));
		assertEquals(1, nothing.getAttacks());
		assertEquals(10, nothing.getTotalDamage());
	}

	@Test
	public void slotsNothingWasEverWornInAreNotOffered()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		store.recordAttack(context(4151, CombatStyle.MELEE, 4, 100), 0, 4);

		final FilterOptions options = store.options(false, HistoryFilter.ALL);
		assertTrue(options.getGearBySlot().containsKey(CombatContext.WEAPON_SLOT));
		assertFalse(options.getGearBySlot().containsKey(0));
	}

	@Test
	public void optionListsNarrowToTheOtherSelections()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whipOnRat = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext bowOnRat = context(11235, CombatStyle.RANGED, 6, 100);
		final CombatContext whipOnBear = context(4151, CombatStyle.MELEE, 4, 200);

		store.recordAttack(whipOnRat, 0, 4);
		store.recordAttack(bowOnRat, 0, 6);
		store.recordAttack(whipOnBear, 0, 4);

		// Nothing selected: every monster, weapon and attack is offered.
		final FilterOptions all = store.options(false, HistoryFilter.ALL);
		assertEquals(2, all.getNpcs().size());
		assertEquals(2, all.getWeapons().size());
		assertEquals(2, all.getAttacks().size());

		// Pick the whip: only Melee remains as an attack, but both weapons stay selectable and
		// both monsters stay listed because the whip was used on each.
		final HistoryFilter whipPicked = new HistoryFilter(null, null, weapon(4151), null, null);
		final FilterOptions withWhip = store.options(false, whipPicked);
		assertEquals(1, withWhip.getAttacks().size());
		assertEquals("Aggressive", withWhip.getAttacks().get(0).getLabel());
		assertEquals(2, withWhip.getWeapons().size());
		assertEquals(2, withWhip.getNpcs().size());

		// Pick the bear: the whip is the only weapon that ever hit it.
		final HistoryFilter bearPicked = new HistoryFilter("NPC #200", null, null, null, null);
		final FilterOptions withBear = store.options(false, bearPicked);
		assertEquals(1, withBear.getWeapons().size());
		assertEquals(Integer.valueOf(4151), withBear.getWeapons().get(0).getId());
		assertEquals(1, withBear.getAttacks().size());
		assertEquals(2, withBear.getNpcs().size());

		// A combination with no data behind it: the attack list, which depends on both, empties.
		final HistoryFilter bearAndBow = new HistoryFilter("NPC #200", null, weapon(11235), null, null);
		final FilterOptions empty = store.options(false, bearAndBow);
		assertTrue(empty.getAttacks().isEmpty());

		// The monster and weapon lists still offer a way out rather than stranding the user: the
		// rat is where the bow was used, and the whip is what the bear was hit with.
		assertEquals(1, empty.getNpcs().size());
		assertEquals("NPC #100", empty.getNpcs().get(0).getLabel());
		assertEquals(1, empty.getWeapons().size());
		assertEquals(Integer.valueOf(4151), empty.getWeapons().get(0).getId());
	}

	@Test
	public void optionsListEverythingSeenOrderedByAttacks()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext bow = context(11235, CombatStyle.RANGED, 6, 200);
		store.recordAttack(whip, 0, 4);
		store.recordAttack(whip, 0, 4);
		store.recordAttack(bow, 0, 6);

		final FilterOptions options = store.options(false);
		assertEquals(2, options.getNpcs().size());
		assertEquals("NPC #100", options.getNpcs().get(0).getLabel());
		assertEquals("NPC #100", options.getNpcs().get(0).getName());
		assertEquals(2, options.getWeapons().size());
		assertEquals(Integer.valueOf(4151), options.getWeapons().get(0).getId());
		assertEquals("Aggressive", options.getAttacks().get(0).getLabel());

		final FilterOptions byId = store.options(true);
		assertEquals(Integer.valueOf(100), byId.getNpcs().get(0).getId());
		assertTrue(byId.getNpcs().get(0).getLabel().contains("#100"));
	}

	@Test
	public void roundTripsThroughGson()
	{
		final Gson gson = new Gson();
		final HistoryData data = new HistoryData();
		final CombatContext prayed = AttackMatcherTest.builder(CombatStyle.MELEE, 4, 100)
			.targetOverheads(Collections.singletonList("PROTECT_FROM_MELEE"))
			.styleProtected(true)
			.build();
		final ContextStats stats = new ContextStats(prayed);
		stats.recordAttack(1, 4);
		stats.recordHit(12, true);
		data.getContexts().put(prayed.getKey(), stats);
		data.getNpcNames().put(100, new NpcName("Zulrah", 725));
		data.getItemNames().put(4151, "Abyssal whip");
		data.getFights().add(new KillRecord(5L, 100, prayed.getKey(), 4151, 20, 5, 5, 50, 1, 0, 2, true));

		final String json = gson.toJson(data);
		final HistoryData back = gson.fromJson(json, HistoryData.class);

		final ContextStats loaded = back.getContexts().get(prayed.getKey());
		assertEquals(prayed.getKey(), loaded.getContext().getKey());
		assertEquals(1, loaded.getAttacks());
		assertEquals(12, loaded.getHighestHit());
		assertEquals(1, loaded.getMaxHits());
		assertTrue(loaded.getContext().isStyleProtected());
		assertEquals(Collections.singletonList("PROTECT_FROM_MELEE"), loaded.getContext().getTargetOverheads());
		assertEquals("Zulrah", back.getNpcNames().get(100).getName());
		assertEquals("Abyssal whip", back.getItemNames().get(4151));
		assertEquals(1, back.getFights().size());
		assertTrue(back.getFights().get(0).isKilled());
		assertEquals(HistoryData.CURRENT_VERSION, back.getVersion());
	}

	@Test
	public void hitsAreLoggedIndividuallyNewestFirst()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext bow = context(11235, CombatStyle.RANGED, 6, 200);

		store.recordHit(whip, 12, false);
		store.recordHit(whip, 0, false);
		store.recordSplash(bow);
		store.recordHit(bow, 31, true);

		final List<HitRecord> all = store.recentHits(HistoryFilter.ALL, 10);
		assertEquals(4, all.size());

		// Newest first, so the panel reads like a log.
		assertEquals(31, all.get(0).getDamage());
		assertTrue(all.get(0).isMax());
		assertTrue(all.get(1).isSplash());
		assertEquals(0, all.get(2).getDamage());
		assertEquals(12, all.get(3).getDamage());

		assertEquals(200, all.get(0).getNpcId());
		assertEquals(11235, all.get(0).getWeaponId());
		assertEquals(whip.getKey(), all.get(3).getContextKey());
	}

	@Test
	public void theHitLogObeysTheFilterAndTheLimit()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext bow = context(11235, CombatStyle.RANGED, 6, 200);

		for (int i = 0; i < 5; i++)
		{
			store.recordHit(whip, 10 + i, false);
			store.recordHit(bow, 20 + i, false);
		}

		assertEquals(3, store.recentHits(HistoryFilter.ALL, 3).size());

		final List<HitRecord> whipHits = store.recentHits(new HistoryFilter(null, null, weapon(4151), null, null), 10);
		assertEquals(5, whipHits.size());
		for (HitRecord hit : whipHits)
		{
			assertEquals(4151, hit.getWeaponId());
		}
	}

	@Test
	public void theHitLogIsCappedSoTheFileCannotGrowForever()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		store.setHitLogSize(10);

		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		for (int i = 0; i < 50; i++)
		{
			store.recordHit(whip, i, false);
		}

		final List<HitRecord> kept = store.recentHits(HistoryFilter.ALL, 100);
		assertEquals(10, kept.size());
		// The most recent survive; the oldest are dropped.
		assertEquals(49, kept.get(0).getDamage());
		assertEquals(40, kept.get(9).getDamage());

		// Shrinking the cap trims what is already there.
		store.setHitLogSize(3);
		assertEquals(3, store.recentHits(HistoryFilter.ALL, 100).size());

		// The aggregates keep every hit regardless; only the log is capped.
		assertEquals(50, store.aggregate(HistoryFilter.ALL).getHitsplats());
	}

	@Test
	public void sanitisesFileNames()
	{
		assertEquals("Some Name_1", HitStatsStore.sanitise("Some Name/1"));
		assertEquals("a_b", HitStatsStore.sanitise("a:b"));
	}


	@Test
	public void everythingRecordedCountsInBothScopesUntilTheSessionIsCleared()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);
		store.recordFight(new KillRecord(1L, 100, whip.getKey(), 4151, 4, 1, 1, 10, 0, 0, 0, true));

		assertEquals(10, store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).getTotalDamage());
		assertEquals(10, store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).getTotalDamage());
		assertEquals(1, store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).getKills());

		store.clear(HistoryScope.SESSION);

		// The session starts over; the lifetime totals are untouched.
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).isEmpty());
		assertEquals(0, store.getContextCount(HistoryScope.SESSION));
		assertEquals(0, store.recentHits(HistoryFilter.ALL, 10, HistoryScope.SESSION).size());
		assertEquals(10, store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).getTotalDamage());
		assertEquals(1, store.getContextCount(HistoryScope.ALL_TIME));
		assertEquals(1, store.recentHits(HistoryFilter.ALL, 10, HistoryScope.ALL_TIME).size());

		// What is recorded after the clear lands in both again.
		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 5, false);
		assertEquals(5, store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).getTotalDamage());
		assertEquals(15, store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).getTotalDamage());
	}

	@Test
	public void clearingEverythingTakesTheSessionWithIt()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);

		store.clear(HistoryScope.ALL_TIME);

		// The session is a subset of what was just deleted, so it cannot survive it.
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).isEmpty());
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).isEmpty());
	}

	@Test
	public void theHitLogAndTheFilterListsFollowTheScope()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);
		final CombatContext bow = context(11235, CombatStyle.RANGED, 6, 200);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);

		store.clear(HistoryScope.SESSION);

		store.recordAttack(bow, 0, 6);
		store.recordHit(bow, 30, false);

		final List<HitRecord> sessionHits = store.recentHits(HistoryFilter.ALL, 10, HistoryScope.SESSION);
		assertEquals(1, sessionHits.size());
		assertEquals(30, sessionHits.get(0).getDamage());
		assertEquals(2, store.recentHits(HistoryFilter.ALL, 10, HistoryScope.ALL_TIME).size());

		// A weapon only used before the clear is not offered as a session filter.
		final List<FilterOptions.Option> sessionWeapons =
			store.options(false, HistoryFilter.ALL, HistoryScope.SESSION).getWeapons();
		assertEquals(1, sessionWeapons.size());
		assertEquals(Integer.valueOf(11235), sessionWeapons.get(0).getId());
		assertEquals(2, store.options(false, HistoryFilter.ALL, HistoryScope.ALL_TIME).getWeapons().size());
	}

	@Test
	public void killingBlowsCanBeLeftOutOfTheDistribution()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);
		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 30, true);
		store.markKillingBlow(whip, 30, true);

		final Aggregate with = store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME, true);
		assertEquals(1, with.getCounts()[30]);
		assertEquals(1, with.getKillCounts()[30]);
		assertEquals(1, with.getKillingBlows());
		assertEquals(0, with.getExcludedKillingBlows());
		assertEquals(40, with.getTotalDamage());
		assertEquals(30, with.getHighestHit());
		assertEquals(20.0, with.getAveragePerHitsplat(), EPS);
		assertEquals(0.5, with.getMaxHitRate(), EPS);

		final Aggregate without = store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME, false);
		assertEquals(0, without.getCounts()[30]);
		assertEquals(1, without.getExcludedKillingBlows());

		// The shape of the distribution changes.
		assertEquals(10, without.getHighestHit());
		assertEquals(10.0, without.getAveragePerHitsplat(), EPS);
		assertEquals(10.0, without.getAveragePerLandedHit(), EPS);
		assertEquals(0.0, without.getMaxHitRate(), EPS);

		// What happened does not.
		assertEquals(40, without.getTotalDamage());
		assertEquals(20.0, without.getAveragePerAttack(), EPS);
		assertEquals(2, without.getHitsplats());
		assertEquals(1.0, without.getAccuracy(), EPS);
		assertEquals(40 / (8 * 0.6), without.getDps(), EPS);

		// The log entry is flagged, once, and the same record is in every scope.
		final List<HitRecord> hits = store.recentHits(HistoryFilter.ALL, 10, HistoryScope.SESSION);
		assertTrue(hits.get(0).isKillingBlow());
		assertFalse(hits.get(1).isKillingBlow());
		assertTrue(store.recentHits(HistoryFilter.ALL, 10, HistoryScope.CURRENT_FIGHT).get(0).isKillingBlow());
	}

	@Test
	public void theFightWindowKeepsAKillUntilTheNextAttack()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT).isEmpty());
		assertFalse(store.isFightOver());

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);
		store.recordFight(new KillRecord(1L, 100, whip.getKey(), 4151, 4, 1, 1, 10, 0, 0, 0, true));

		// The kill stays on screen.
		assertTrue(store.isFightOver());
		assertEquals(100, store.getLastKillNpcId());
		Aggregate current = store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT);
		assertEquals(10, current.getTotalDamage());
		assertEquals(1, current.getKills());

		// The next attack starts over; the session and the file keep both.
		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 7, false);
		assertFalse(store.isFightOver());
		current = store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT);
		assertEquals(7, current.getTotalDamage());
		assertEquals(0, current.getKills());
		assertEquals(1, store.recentHits(HistoryFilter.ALL, 10, HistoryScope.CURRENT_FIGHT).size());
		assertEquals(17, store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).getTotalDamage());
		assertEquals(17, store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).getTotalDamage());

		// Clearing the session takes the fight window with it.
		store.clear(HistoryScope.SESSION);
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT).isEmpty());
		assertFalse(store.isFightOver());
	}

	@Test
	public void aFightThatEndsWithoutAKillDoesNotEndTheWindow()
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);
		store.recordFight(new KillRecord(1L, 100, whip.getKey(), 4151, 4, 1, 1, 10, 0, 0, 0, false));
		assertFalse(store.isFightOver());

		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 7, false);
		assertEquals(17, store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT).getTotalDamage());
		assertEquals(1, store.aggregate(HistoryFilter.ALL, HistoryScope.CURRENT_FIGHT).getFights());
	}

	@Test
	public void aRelogContinuesTheSessionAndAnotherCharacterStartsANewOne() throws Exception
	{
		final HitStatsStore store = new HitStatsStore(new Gson());
		store.setDirectory(folder.getRoot());
		final CombatContext whip = context(4151, CombatStyle.MELEE, 4, 100);

		store.load("Alice");
		store.recordAttack(whip, 0, 4);
		store.recordHit(whip, 10, false);

		// A hop, a dropped connection or a logout: unloaded and loaded again as the same player.
		store.unload();
		store.load("Alice");
		assertEquals(10, store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).getTotalDamage());
		assertEquals(10, store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).getTotalDamage());

		store.unload();
		store.load("Bob");
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.SESSION).isEmpty());
		assertTrue(store.aggregate(HistoryFilter.ALL, HistoryScope.ALL_TIME).isEmpty());
	}

	private static Map<Integer, Integer> weapon(int itemId)
	{
		return Collections.singletonMap(CombatContext.WEAPON_SLOT, itemId);
	}

	private static CombatContext context(int weaponId, CombatStyle style, int speed, int npcId)
	{
		final int[] gear = new int[CombatContext.GEAR_SLOTS];
		Arrays.fill(gear, -1);
		gear[CombatContext.WEAPON_SLOT] = weaponId;

		return AttackMatcherTest.builder(style, speed, npcId)
			.gear(gear)
			.styleName(style == CombatStyle.RANGED ? "Rapid" : "Aggressive")
			.build();
	}

	private static int[] gearWith(int weaponId, int slot, int itemId)
	{
		final int[] gear = new int[CombatContext.GEAR_SLOTS];
		Arrays.fill(gear, -1);
		gear[CombatContext.WEAPON_SLOT] = weaponId;
		gear[slot] = itemId;
		return gear;
	}

	private static int[] zeroExcept(int[] counts, int index)
	{
		final int[] copy = Arrays.copyOf(counts, counts.length);
		copy[index] = 0;
		return copy;
	}
}
