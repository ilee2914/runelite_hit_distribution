package com.github.ilee2.hitdistribution;

import com.github.ilee2.hitdistribution.ui.HitDistributionPanel;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Records every hit the player deals, grouped by the gear, stats, prayers, style, spell and
 * target it was dealt under, and shows the resulting damage distribution in a side panel.
 */
@Slf4j
@PluginDescriptor(
	name = "Hit Distribution",
	description = "Damage distribution, splash rate, DPS and wasted ticks per monster, weapon, gear and stats",
	tags = {"damage", "hit", "dps", "splash", "accuracy", "combat", "history", "tracker", "distribution"}
)
public class HitDistributionPlugin extends Plugin
{
	private static final long PANEL_REFRESH_SECONDS = 2;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HitDistributionConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CombatTracker tracker;

	@Inject
	private HitDistributionStore store;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ScheduledExecutorService executor;

	private HitDistributionPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refreshTask;
	private long shownRevision = -1;

	@Provides
	HitDistributionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HitDistributionConfig.class);
	}

	@Override
	protected void startUp()
	{
		store.setHitLogSize(config.hitLogSize());
		panel = new HitDistributionPanel(store, config, configManager, itemManager, spriteManager,
			this::clearHistory);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/hit_distribution_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Hit Distribution")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshTask = executor.scheduleAtFixedRate(this::refreshPanelIfChanged,
			PANEL_REFRESH_SECONDS, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}

		clientThread.invoke(() ->
		{
			tracker.flush();
			tracker.reset();
			store.unload();
		});

		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (HitDistributionConfig.GROUP.equals(event.getGroup()))
		{
			store.setHitLogSize(config.hitLogSize());
			shownRevision = -1;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			tracker.flush();
			tracker.reset();
			store.unload();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		tracker.onGameTick();
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		tracker.onAnimationChanged(event.getActor());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		tracker.onHitsplatApplied(event);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		tracker.onGraphicChanged(event);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		tracker.onActorDeath(event.getActor());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		tracker.onNpcDespawned(event.getNpc());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		tracker.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		tracker.onVarbitChanged(event);
	}

	private void refreshPanelIfChanged()
	{
		final HitDistributionPanel current = panel;
		if (current == null)
		{
			return;
		}

		final long revision = store.getRevision();
		if (revision == shownRevision)
		{
			return;
		}
		shownRevision = revision;
		SwingUtilities.invokeLater(current::refresh);
	}

	private void clearHistory(HistoryScope scope)
	{
		clientThread.invoke(() ->
		{
			// Either way the in-flight state goes: a fight that opened before the clear would
			// otherwise close afterwards and write ticks and damage the panel no longer counts.
			tracker.reset();
			store.clear(scope);
		});
	}
}
