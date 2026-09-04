package com.github.ilee2.hitstats;

import com.github.ilee2.hitstats.sync.CommunityClient;
import com.github.ilee2.hitstats.sync.CommunitySync;
import com.github.ilee2.hitstats.sync.OkHttpTransport;
import com.github.ilee2.hitstats.sync.SyncTransport;
import com.github.ilee2.hitstats.ui.HitStatsPanel;
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
import net.runelite.client.events.ClientShutdown;
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
	name = "Hit Stats",
	description = "Damage distribution, splash rate, DPS and wasted ticks per monster, weapon, gear and stats",
	tags = {"damage", "hit", "dps", "splash", "accuracy", "combat", "history", "tracker", "distribution"}
)
public class HitStatsPlugin extends Plugin
{
	private static final long PANEL_REFRESH_SECONDS = 2;

	/** How long after logging in the catch-up upload runs, so it never competes with the login. */
	private static final int LOGIN_UPLOAD_DELAY_TICKS = 17;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HitStatsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CombatTracker tracker;

	@Inject
	private HitStatsStore store;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private CommunitySync communitySync;

	@Inject
	private CommunityClient communityClient;

	private HitStatsPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refreshTask;
	private ScheduledFuture<?> uploadTask;
	private long shownRevision = -1;
	private long shownCommunityRevision = -1;

	/** Client tick the catch-up upload is due on, or -1 when none is pending. */
	private int loginUploadTick = -1;

	@Provides
	HitStatsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HitStatsConfig.class);
	}

	@Provides
	SyncTransport provideTransport(OkHttpTransport transport)
	{
		return transport;
	}

	@Override
	protected void startUp()
	{
		store.setHitLogSize(config.hitLogSize());
		panel = new HitStatsPanel(store, config, configManager, itemManager, spriteManager,
			communityClient, communitySync, this::clearHistory);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/hit_stats_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Hit Stats")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshTask = executor.scheduleAtFixedRate(this::refreshPanelIfChanged,
			PANEL_REFRESH_SECONDS, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
		scheduleUploads();
	}

	/**
	 * Runs the community upload on its own timer, well apart from the autosave. It does nothing at
	 * all unless the player has opted in, and skips silently when nothing has changed.
	 */
	private void scheduleUploads()
	{
		if (uploadTask != null)
		{
			uploadTask.cancel(false);
		}
		final long minutes = Math.max(1, config.uploadMinutes());
		uploadTask = executor.scheduleWithFixedDelay(
			() -> communitySync.upload(CommunitySync.Reason.TIMER), minutes, minutes, TimeUnit.MINUTES);
	}

	@Override
	protected void shutDown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}
		if (uploadTask != null)
		{
			uploadTask.cancel(false);
			uploadTask = null;
		}
		communityClient.clear();

		clientThread.invoke(() ->
		{
			tracker.flush();
			communitySync.upload(CommunitySync.Reason.LOGOUT);
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
		if (HitStatsConfig.GROUP.equals(event.getGroup()))
		{
			store.setHitLogSize(config.hitLogSize());
			if ("uploadMinutes".equals(event.getKey()))
			{
				scheduleUploads();
			}
			if ("levelMatch".equals(event.getKey()) || "showCommunity".equals(event.getKey()))
			{
				communityClient.clear();
			}
			shownRevision = -1;
		}
	}

	/**
	 * Holds the client's shutdown for a moment so a last upload can finish. It cannot cover a
	 * power cut; the catch-up after the next login is what covers that.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		event.waitFor(communitySync.upload(CommunitySync.Reason.SHUTDOWN));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			tracker.flush();
			// Before the unload: the batch is built on this thread, and a moment later there is no
			// file to build it from.
			communitySync.upload(CommunitySync.Reason.LOGOUT);
			tracker.reset();
			store.unload();
			loginUploadTick = -1;
		}
		else if (state == GameState.LOGGED_IN && loginUploadTick < 0)
		{
			loginUploadTick = client.getTickCount() + LOGIN_UPLOAD_DELAY_TICKS;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		tracker.onGameTick();

		// The catch-up for whatever a crash or a power cut interrupted, once the login has settled.
		if (loginUploadTick >= 0 && client.getTickCount() >= loginUploadTick)
		{
			loginUploadTick = -1;
			communitySync.upload(CommunitySync.Reason.LOGIN);
		}
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
		final HitStatsPanel current = panel;
		if (current == null)
		{
			return;
		}

		// Either the recorded history or a community answer can change what the panel should show.
		final long revision = store.getRevision();
		final long communityRevision = communityClient.getRevision();
		if (revision == shownRevision && communityRevision == shownCommunityRevision)
		{
			return;
		}
		shownCommunityRevision = communityRevision;
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
