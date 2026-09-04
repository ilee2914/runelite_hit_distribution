package com.github.ilee2.hitstats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.Text;

/**
 * Turns client events into store writes. Owns the {@link AttackMatcher}, the open fights, and
 * the small amount of state needed to know what the player was doing when an animation fired.
 * Everything here runs on the client thread.
 */
@Slf4j
@Singleton
class CombatTracker
{
	/** A fight with no hit or attack for this long is over, whatever happened to the NPC. */
	private static final int FIGHT_TIMEOUT_TICKS = 100;

	/** How long a clicked spell waits for the cast animation before being forgotten. */
	private static final int MANUAL_CAST_TIMEOUT_TICKS = 25;

	private static final int UNARMED_SPEED = 4;
	private static final int SPELL_SPEED = 5;
	private static final int HARMONISED_SPELL_SPEED = 4;

	/** Must stay in {@link CombatContext#SKILL_NAMES} order. */
	private static final Skill[] SKILLS = {
		Skill.ATTACK, Skill.STRENGTH, Skill.RANGED, Skill.MAGIC,
	};

	private final Client client;
	private final ItemManager itemManager;
	private final AttackStyleResolver styleResolver;
	private final HitStatsStore store;
	private final HitStatsConfig config;

	private AttackMatcher matcher;

	private final Map<Integer, Fight> fights = new HashMap<>();
	private final List<OrphanHit> orphans = new ArrayList<>();
	private final Set<Integer> splashedThisTick = new HashSet<>();
	private int splashTick = -1;

	@Nullable
	private PendingAttack lastRecorded;

	/** The NPC an animation event named this tick, held until the tick has settled. */
	@Nullable
	private NPC animatedTarget;
	private int animatedTick = -1;

	@Nullable
	private String manualSpell;
	private int manualSpellTick = -1;

	private int specEnergy = -1;
	private int specDropTick = -1;

	private int lastSaveTick;

	@Inject
	CombatTracker(Client client, ItemManager itemManager, AttackStyleResolver styleResolver,
		HitStatsStore store, HitStatsConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.styleResolver = styleResolver;
		this.store = store;
		this.config = config;
	}

	// ----------------------------------------------------------------- lifecycle

	void reset()
	{
		closeAllFights(false);
		if (matcher != null)
		{
			matcher.reset();
		}
		orphans.clear();
		splashedThisTick.clear();
		lastRecorded = null;
		animatedTarget = null;
		animatedTick = -1;
		manualSpell = null;
		specEnergy = -1;
		specDropTick = -1;
		styleResolver.reset();
	}

	/** Finishes open fights and writes the file. Called on logout and shutdown. */
	void flush()
	{
		closeAllFights(false);
		store.save();
	}

	// -------------------------------------------------------------------- events

	void onGameTick()
	{
		final int tick = client.getTickCount();
		ensureMatcher();

		if (!store.isLoaded())
		{
			final Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null)
			{
				store.load(local.getName());
				lastSaveTick = tick;
			}
		}

		styleResolver.update();
		captureAttack(tick);
		retryOrphans(tick);
		closeDeadFights();
		matcher.expire(tick);
		expireFights(tick);

		if (splashTick != tick)
		{
			splashedThisTick.clear();
			splashTick = tick;
		}

		final int saveEvery = Math.max(1, config.autosaveMinutes()) * 100;
		if (tick - lastSaveTick >= saveEvery)
		{
			lastSaveTick = tick;
			store.save();
		}
	}

	void onAnimationChanged(Actor actor)
	{
		final Player local = client.getLocalPlayer();
		if (local == null || actor != local || local.getAnimation() == -1)
		{
			return;
		}

		final Actor interacting = local.getInteracting();
		if (interacting instanceof NPC)
		{
			// Only remember that it happened. The context is built at the end of the tick,
			// because the worn container and the combat-style varbits arrive as their own events
			// and an animation can be delivered before them. Snapshotting here paired the weapon
			// that had just been equipped with the style of the one before it, which is how a bow
			// ended up recorded as magic.
			animatedTarget = (NPC) interacting;
			animatedTick = client.getTickCount();
		}
	}

	/**
	 * Builds the attack for this tick, once everything the snapshot reads has settled.
	 *
	 * <p>Two ways in. Usually an animation event named the target earlier in the tick. Failing
	 * that, the player may simply be animating at an NPC: an animation held across ticks, or
	 * re-set to the id it already had, produces no event at all, and the hits from those attacks
	 * would otherwise arrive with no attack to attribute them to.
	 *
	 * <p>A false positive costs nothing: the matcher discards anything inside the previous
	 * attack's cooldown, and an attack that never matches a hitsplat or a splash is never
	 * recorded, so eating or an emote mid-fight never reaches the store.
	 */
	private void captureAttack(int tick)
	{
		if (matcher.findByTick(tick) != null)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		NPC target = null;
		boolean fromEvent = false;

		if (animatedTick == tick && animatedTarget != null)
		{
			target = animatedTarget;
			fromEvent = true;
		}
		else if (local.getAnimation() != -1 && local.getInteracting() instanceof NPC)
		{
			target = (NPC) local.getInteracting();
		}

		animatedTarget = null;

		if (target != null)
		{
			offerAttack(target, fromEvent, tick);
		}
	}

	/**
	 * @param fromEvent whether an animation event named this target. Only that path carries a
	 * manually cast spell, and only an attack the matcher accepts consumes it: a block animation
	 * from being hit while waiting for the cast to fire is also an event, and letting it take the
	 * spell left the real cast recorded under the combat tab's style, as melee.
	 */
	private void offerAttack(NPC target, boolean fromEvent, int tick)
	{
		ensureMatcher();
		if (matcher.inCooldown(tick))
		{
			// Cannot be an attack, and the matcher would say so. Checked before the snapshot
			// because a held animation lands here every tick, and the snapshot is a hash of
			// every worn item, level, prayer and the target's overhead.
			return;
		}

		final boolean special = specDropTick == tick || client.getVarpValue(VarPlayerID.SA_ATTACK) == 1;

		String spell = null;
		if (fromEvent && manualSpell != null && tick - manualSpellTick <= MANUAL_CAST_TIMEOUT_TICKS)
		{
			spell = manualSpell;
		}

		final CombatContext context = snapshot(target, special, spell);
		final PendingAttack attack = new PendingAttack(tick, target.getIndex(), context);
		if (matcher.offer(attack))
		{
			if (spell != null)
			{
				manualSpell = null;
			}
			store.rememberNpc(target);
			if (config.debugLog())
			{
				log.debug("tick {} attack on {}#{} weapon={} style={} spell={} speed={} spec={} overhead={}{}{}",
					tick, target.getName(), target.getIndex(), context.getWeaponId(), context.getStyleName(),
					context.getSpellName(), context.getAttackSpeed(), special, context.getTargetPrayerLabel(),
					context.isStyleProtected() ? " (blocks this style)" : "", fromEvent ? "" : " [no animation event]");
			}
		}
	}

	void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}

		final Hitsplat hitsplat = event.getHitsplat();
		if (!hitsplat.isMine())
		{
			return;
		}

		ensureMatcher();
		final NPC npc = (NPC) event.getActor();
		final int tick = client.getTickCount();
		final boolean max = isMaxHit(hitsplat.getHitsplatType());

		if (!applyHit(tick, npc, hitsplat.getAmount(), max))
		{
			// The hitsplat can arrive in the same tick as, but ahead of, the animation. Hold it
			// until the tick has been fully processed.
			orphans.add(new OrphanHit(tick, npc, hitsplat.getAmount(), max));
		}
	}

	void onGraphicChanged(GraphicChanged event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}

		final NPC npc = (NPC) event.getActor();
		if (!npc.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT))
		{
			return;
		}

		ensureMatcher();
		final int tick = client.getTickCount();
		if (splashTick != tick)
		{
			splashedThisTick.clear();
			splashTick = tick;
		}
		if (!splashedThisTick.add(npc.getIndex()))
		{
			return;
		}

		final PendingAttack attack = matcher.matchSplash(tick, npc.getIndex());
		if (attack == null)
		{
			// Somebody else's spell splashed on this NPC.
			return;
		}

		recordAttackIfNeeded(attack, tick);
		store.recordSplash(attack.getContext());
		final Fight fight = fightFor(npc, attack, tick);
		fight.splashes++;
		fight.lastTick = tick;

		if (config.debugLog())
		{
			log.debug("tick {} splash on {}#{} (attack tick {})", tick, npc.getName(), npc.getIndex(), attack.getTick());
		}
	}

	/**
	 * A death is noted here and acted on at the end of the tick. The killing hitsplat lands on
	 * the same tick, and nothing promises it is delivered first; closing at once would either
	 * miss it or mark the wrong hits.
	 */
	void onActorDeath(Actor actor)
	{
		if (!(actor instanceof NPC))
		{
			return;
		}
		final Fight fight = fights.get(((NPC) actor).getIndex());
		if (fight != null && fight.deathTick < 0)
		{
			fight.deathTick = client.getTickCount();
		}
	}

	void onNpcDespawned(NPC npc)
	{
		final Fight fight = fights.get(npc.getIndex());
		if (fight == null)
		{
			return;
		}
		if (npc.isDead())
		{
			if (fight.deathTick < 0)
			{
				fight.deathTick = client.getTickCount();
			}
			return;
		}
		fights.remove(npc.getIndex());
		closeFight(fight, false, client.getTickCount());
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		// A spell cast by hand does not change the attack style varp or the autocast varp, so the
		// only record of which spell it was is the menu entry.
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_NPC || !"Cast".equals(event.getMenuOption()))
		{
			return;
		}

		final String rawTarget = event.getMenuTarget();
		if (rawTarget == null)
		{
			return;
		}
		final String target = Text.removeTags(rawTarget);
		final int arrow = target.indexOf(" -> ");
		manualSpell = arrow > 0 ? target.substring(0, arrow).trim() : target.trim();
		manualSpellTick = client.getTickCount();
	}

	void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.SA_ENERGY)
		{
			return;
		}

		// Contexts are built at the end of the tick, after every varp update in it, so noting the
		// tick is enough; the snapshot reads it.
		final int value = event.getValue();
		if (specEnergy >= 0 && value < specEnergy)
		{
			specDropTick = client.getTickCount();
		}
		specEnergy = value;
	}

	// --------------------------------------------------------------- matching

	private boolean applyHit(int tick, NPC npc, int amount, boolean max)
	{
		final PendingAttack attack = matcher.matchHit(tick, npc.getIndex());
		if (attack == null)
		{
			return false;
		}

		recordAttackIfNeeded(attack, tick);
		store.rememberNpc(npc);
		final Fight fight = fightFor(npc, attack, tick);

		if (attack.isResolvedBySplash() && attack.getNpcIndex() == npc.getIndex())
		{
			// A splash graphic carries no owner. This hitsplat is ours and came from this
			// attack, and an attack cannot both splash and hit, so the splash credited to it
			// was another player's. Take it back.
			attack.setResolvedBySplash(false);
			store.undoSplash(attack.getContext());
			if (fight.splashes > 0)
			{
				fight.splashes--;
			}
			if (config.debugLog())
			{
				log.debug("tick {} splash on {}#{} taken back: attack tick {} produced a hitsplat", tick,
					npc.getName(), npc.getIndex(), attack.getTick());
			}
		}

		store.recordHit(attack.getContext(), amount, max);
		fight.hitsplats++;
		fight.damage += amount;
		if (amount == 0)
		{
			fight.misses++;
		}
		fight.lastTick = tick;

		// Kept so the hits on the death tick can be found once the death is seen.
		if (fight.hitsTick != tick)
		{
			fight.tickHits.clear();
			fight.hitsTick = tick;
		}
		fight.tickHits.add(new TickHit(attack.getContext(), amount, max));

		if (config.debugLog())
		{
			log.debug("tick {} hit {} on {}#{} (attack tick {}{})", tick, amount, npc.getName(), npc.getIndex(),
				attack.getTick(), max ? ", max" : "");
		}
		return true;
	}

	private void retryOrphans(int tick)
	{
		final Iterator<OrphanHit> it = orphans.iterator();
		while (it.hasNext())
		{
			final OrphanHit orphan = it.next();
			// The hit's own tick, not the current one: a retry a tick later must not stamp the
			// attack, or the fight, as having resolved a tick after it did.
			if (applyHit(orphan.tick, orphan.npc, orphan.amount, orphan.max))
			{
				it.remove();
			}
			else if (tick - orphan.tick >= 1)
			{
				// Vengeance, recoil, or an animation this plugin never saw.
				it.remove();
				store.recordUnattributedHit();
				if (config.debugLog())
				{
					// How far this landed from the last attack on the same NPC. A run of these at
					// a steady one or two ticks would be a weapon whose extra hitsplats land on a
					// later tick than its first, which the matcher does not yet allow for.
					final int resolved = matcher.lastResolvedTick(orphan.npc.getIndex());
					log.debug("tick {} unattributed hit {} on {}#{}{}", orphan.tick, orphan.amount,
						orphan.npc.getName(), orphan.npc.getIndex(),
						resolved >= 0 ? " (last attack on it resolved " + (orphan.tick - resolved) + " ticks earlier)" : "");
				}
			}
		}
	}

	private void recordAttackIfNeeded(PendingAttack attack, int tick)
	{
		if (attack.isRecorded())
		{
			return;
		}
		attack.setRecorded(true);

		int wasted = 0;
		int active = attack.getSpeed();
		if (lastRecorded != null)
		{
			final int gap = attack.getTick() - lastRecorded.getTick();
			if (gap > 0 && gap <= config.idleGapTicks())
			{
				wasted = Math.max(0, gap - lastRecorded.getSpeed());
				active = gap;
			}
		}
		lastRecorded = attack;

		store.recordAttack(attack.getContext(), wasted, active);

		final Fight fight = fights.get(attack.getNpcIndex());
		if (fight != null)
		{
			fight.attacks++;
			fight.wastedTicks += wasted;
			fight.lastTick = Math.max(fight.lastTick, tick);
		}
		else
		{
			fights.put(attack.getNpcIndex(), new Fight(attack, wasted));
		}
	}

	private Fight fightFor(NPC npc, PendingAttack attack, int tick)
	{
		Fight fight = fights.get(npc.getIndex());
		if (fight == null)
		{
			// A secondary target of an area attack: the fight starts with the hit rather than
			// with an animation aimed at it.
			fight = new Fight(attack, 0);
			fight.npcId = npc.getId();
			fight.attacks = 0;
			fight.startTick = tick;
			fights.put(npc.getIndex(), fight);
		}
		return fight;
	}

	/** Closes the fights whose NPC died this tick, now that every hit of the tick has been seen. */
	private void closeDeadFights()
	{
		final Iterator<Map.Entry<Integer, Fight>> it = fights.entrySet().iterator();
		while (it.hasNext())
		{
			final Fight fight = it.next().getValue();
			if (fight.deathTick < 0)
			{
				continue;
			}
			it.remove();
			markKillingBlows(fight);
			closeFight(fight, true, fight.deathTick);
		}
	}

	/**
	 * The hits on the death tick are the killing blows. All of them: with a multi-hit weapon
	 * there is no telling which one met the cap, so the conservative reading is that any of them
	 * might have. Hits that arrive after the death, if a weapon ever does that, are simply never
	 * marked; nothing is marked by guesswork.
	 */
	private void markKillingBlows(Fight fight)
	{
		if (fight.hitsTick != fight.deathTick)
		{
			return;
		}
		for (TickHit hit : fight.tickHits)
		{
			if (hit.amount > 0)
			{
				store.markKillingBlow(hit.context, hit.amount, hit.max);
			}
		}
		if (config.debugLog())
		{
			log.debug("tick {} npc {} died: {} hit(s) on the death tick marked as killing blows",
				fight.deathTick, fight.npcId, fight.tickHits.size());
		}
	}

	private void expireFights(int tick)
	{
		final Iterator<Map.Entry<Integer, Fight>> it = fights.entrySet().iterator();
		while (it.hasNext())
		{
			final Fight fight = it.next().getValue();
			if (tick - fight.lastTick > FIGHT_TIMEOUT_TICKS)
			{
				it.remove();
				closeFight(fight, false, fight.lastTick);
			}
		}
	}

	private void closeAllFights(boolean killed)
	{
		final int tick = client.getTickCount();
		for (Fight fight : fights.values())
		{
			closeFight(fight, killed, tick);
		}
		fights.clear();
	}

	private void closeFight(Fight fight, boolean killed, int endTick)
	{
		if (fight.attacks == 0 && fight.hitsplats == 0 && fight.splashes == 0)
		{
			return;
		}

		final int duration = Math.max(1, Math.min(endTick, fight.lastTick) - fight.startTick + 1);
		store.recordFight(new KillRecord(fight.startMillis, fight.npcId, fight.contextKey, fight.weaponId,
			duration, fight.attacks, fight.hitsplats, fight.damage, fight.misses, fight.splashes,
			fight.wastedTicks, killed));

		if (config.debugLog())
		{
			log.debug("fight vs npc {} over: killed={} ticks={} attacks={} damage={}", fight.npcId, killed,
				duration, fight.attacks, fight.damage);
		}
	}

	// --------------------------------------------------------------- snapshot

	private CombatContext snapshot(NPC target, boolean special, @Nullable String manualSpell)
	{
		final int[] gear = new int[CombatContext.GEAR_SLOTS];
		Arrays.fill(gear, CombatContext.NO_ITEM);
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			for (int slot = 0; slot < CombatContext.GEAR_SLOTS; slot++)
			{
				final Item item = worn.getItem(slot);
				if (item != null && item.getId() > 0)
				{
					gear[slot] = item.getId();
					final ItemComposition composition = itemManager.getItemComposition(item.getId());
					store.rememberItem(item.getId(), composition != null ? composition.getName() : null);
				}
			}
		}

		final int[] boosted = new int[SKILLS.length];
		final int[] real = new int[SKILLS.length];
		for (int i = 0; i < SKILLS.length; i++)
		{
			boosted[i] = client.getBoostedSkillLevel(SKILLS[i]);
			real[i] = client.getRealSkillLevel(SKILLS[i]);
		}

		// Only prayers that move the damage dealt. A protection or defence prayer changes nothing
		// about our own hitsplats, and flipping one mid-fight would split the distribution in two.
		final List<String> prayers = new ArrayList<>();
		for (Prayer prayer : Prayer.values())
		{
			if (DamagePrayers.affectsDamage(prayer) && client.getVarbitValue(prayer.getVarbit()) == 1)
			{
				prayers.add(prayer.name());
			}
		}

		styleResolver.update();
		CombatStyle style = styleResolver.getCurrentStyle();
		int spellId = 0;
		String spellName = null;

		if (manualSpell != null)
		{
			style = CombatStyle.MAGIC;
			spellId = AutocastSpell.id(manualSpell);
			spellName = spellId > 0 ? AutocastSpell.name(spellId) : manualSpell;
		}
		else if (style == CombatStyle.MAGIC)
		{
			final int autocast = client.getVarbitValue(VarbitID.AUTOCAST_SPELL);
			if (autocast > 0)
			{
				spellId = autocast;
				spellName = AutocastSpell.name(autocast);
			}
		}

		final int weaponId = gear[CombatContext.WEAPON_SLOT];
		final int speed = attackSpeed(weaponId, style, spellId, spellName, styleResolver.isRapid());

		// The target's overhead at the moment of the attack. An NPC praying against the style
		// being used takes far less damage from it, so it belongs in the key rather than blending
		// two different distributions into one average.
		final Set<OverheadPrayer> overheads = OverheadPrayerReader.read(target);
		final List<String> overheadNames = new ArrayList<>(overheads.size());
		for (OverheadPrayer overhead : overheads)
		{
			overheadNames.add(overhead.name());
		}

		return CombatContext.builder()
			.gear(gear)
			.boosted(boosted)
			.real(real)
			.prayers(prayers)
			.weaponCategory(styleResolver.getWeaponCategory())
			.styleIndex(styleResolver.getStyleIndex())
			.styleName(styleResolver.getCurrentStyleName())
			.combatStyle(style)
			.spellId(spellId)
			.spellName(spellName)
			.special(special)
			.npcId(target.getId())
			.targetOverheads(overheadNames)
			.styleProtected(OverheadPrayerReader.blocks(overheads, style))
			.attackSpeed(speed)
			.build();
	}

	private int attackSpeed(int weaponId, CombatStyle style, int spellId, @Nullable String spellName, boolean rapid)
	{
		if (style == CombatStyle.MAGIC && (spellId != 0 || spellName != null))
		{
			// Spells cast at a fixed rate whatever the staff, except the harmonised nightmare
			// staff on the standard spellbook.
			if (weaponId == ItemID.NIGHTMARE_STAFF_HARMONISED && AutocastSpell.isStandardSpellbook(spellId))
			{
				return HARMONISED_SPELL_SPEED;
			}
			return SPELL_SPEED;
		}

		int speed = UNARMED_SPEED;
		if (weaponId > 0)
		{
			final ItemStats stats = itemManager.getItemStats(weaponId);
			if (stats != null && stats.getEquipment() != null && stats.getEquipment().getAspeed() > 0)
			{
				speed = stats.getEquipment().getAspeed();
			}
		}
		if (rapid)
		{
			speed = Math.max(1, speed - 1);
		}
		return speed;
	}

	private static boolean isMaxHit(int hitsplatType)
	{
		switch (hitsplatType)
		{
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return true;
			default:
				return false;
		}
	}

	private void ensureMatcher()
	{
		final int window = Math.max(1, config.hitWindowTicks());
		if (matcher == null)
		{
			matcher = new AttackMatcher(window);
		}
		else if (matcher.getWindowTicks() != window)
		{
			// Adjust in place: rebuilding would drop the attacks still waiting for their hits.
			matcher.setWindowTicks(window);
		}
	}

	// ------------------------------------------------------------------ types

	/** An open engagement with one NPC instance. */
	private static class Fight
	{
		int npcId;
		final String contextKey;
		final int weaponId;
		final long startMillis = System.currentTimeMillis();
		int startTick;
		int lastTick;
		int attacks;
		int hitsplats;
		int damage;
		int misses;
		int splashes;
		int wastedTicks;

		/** The hits of the latest tick that produced any, and which tick that was. */
		final List<TickHit> tickHits = new ArrayList<>();
		int hitsTick = -1;

		/** The tick the death was seen on, or -1. The fight closes at the end of that tick. */
		int deathTick = -1;

		Fight(PendingAttack first, int wasted)
		{
			this.npcId = first.getContext().getNpcId();
			this.contextKey = first.getContext().getKey();
			this.weaponId = first.getContext().getWeaponId();
			this.startTick = first.getTick();
			this.lastTick = first.getTick();
			this.attacks = 1;
			this.wastedTicks = wasted;
		}
	}

	/** A hit already written to the store, remembered until the tick ends in case it was the kill. */
	private static class TickHit
	{
		final CombatContext context;
		final int amount;
		final boolean max;

		TickHit(CombatContext context, int amount, boolean max)
		{
			this.context = context;
			this.amount = amount;
			this.max = max;
		}
	}

	/** A hitsplat of ours that arrived before its attack animation. */
	private static class OrphanHit
	{
		final int tick;
		final NPC npc;
		final int amount;
		final boolean max;

		OrphanHit(int tick, NPC npc, int amount, boolean max)
		{
			this.tick = tick;
			this.npc = npc;
			this.amount = amount;
			this.max = max;
		}
	}
}
