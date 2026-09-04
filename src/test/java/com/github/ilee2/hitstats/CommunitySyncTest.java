package com.github.ilee2.hitstats;

import com.github.ilee2.hitstats.sync.CommunityAggregate;
import com.github.ilee2.hitstats.sync.CommunityQuery;
import com.github.ilee2.hitstats.sync.CommunitySync;
import com.github.ilee2.hitstats.sync.SyncTransport;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The upload protocol: what goes out, what the watermark does, and every branch of the failure
 * policy. Nothing here touches a network; {@link FakeTransport} stands in for one.
 */
public class CommunitySyncTest
{
	private static final String URL = "http://localhost:8787";

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private HitStatsStore store;
	private FakeTransport transport;
	private FakeConfig config;
	private ScheduledExecutorService executor;
	private CommunitySync sync;

	@Before
	public void setUp()
	{
		store = new HitStatsStore(new Gson());
		store.setDirectory(folder.getRoot());
		store.load("Alice");

		transport = new FakeTransport();
		config = new FakeConfig();
		executor = Executors.newSingleThreadScheduledExecutor();
		sync = new CommunitySync(store, config, null, transport, executor, new Gson());
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	@Test
	public void sendsNothingWhileTheSettingIsOff() throws Exception
	{
		config.uploadEnabled = false;
		recordHit(10);

		await(sync.upload(CommunitySync.Reason.TIMER));

		assertEquals(0, transport.posts);
	}

	@Test
	public void anEmptyOverrideFallsBackToTheBuiltInServer() throws Exception
	{
		// The hidden serverUrl setting is a development override. Blank means "use the built-in
		// one", which has held the deployed Worker since 2026-09-03; it was empty before that,
		// and an empty base URL still disables every request, which is what made the feature
		// inert while there was nothing to talk to.
		config.serverUrl = "";
		recordHit(10);

		assertTrue("the built-in server URL should be set now the Worker is deployed",
			sync.baseUrl().startsWith("https://"));

		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);
	}

	@Test
	public void theOverrideWinsForDevelopmentHostsOnly()
	{
		// FakeConfig starts with a localhost override, so clear it to see the built-in URL.
		config.serverUrl = "";
		final String builtIn = sync.baseUrl();

		config.serverUrl = "http://localhost:8787/";
		assertEquals("localhost is for wrangler dev", "http://localhost:8787", sync.baseUrl());

		config.serverUrl = "   ";
		assertEquals("blank is not an override", builtIn, sync.baseUrl());

		// A hidden setting that could point uploads at any host would be an exfiltration route.
		// Anything outside localhost and this plugin's own subdomain is ignored.
		config.serverUrl = "https://evil.example.com";
		assertEquals("a foreign host must be ignored", builtIn, sync.baseUrl());

		config.serverUrl = "https://osrs-hit-stats-worker.evil.example.com";
		assertEquals("a lookalike host must be ignored", builtIn, sync.baseUrl());

		config.serverUrl = "not a url at all";
		assertEquals("an unparseable override must be ignored", builtIn, sync.baseUrl());
	}

	@Test
	public void theStagingWorkerIsReachableForTesting()
	{
		// Same subdomain as the built-in server, so a sideloaded build can be pointed at staging.
		config.serverUrl = "";
		final String staging = sync.baseUrl().replace("osrs-hit-stats-worker", "osrs-hit-stats-worker-staging");
		config.serverUrl = staging;
		assertEquals(staging, sync.baseUrl());
	}

	@Test
	public void sendsGzippedCumulativeCountersAndAdvancesTheWatermark() throws Exception
	{
		recordHit(10);
		recordHit(10);

		await(sync.upload(CommunitySync.Reason.TIMER));

		assertEquals(1, transport.posts);
		final String json = transport.lastBodyAsJson();
		assertTrue("the body should decompress to JSON", json.startsWith("{"));
		assertTrue(json.contains("\"v\":1"));
		assertTrue(json.contains("\"keyVersion\":8"));
		assertTrue(json.contains("\"uploader\""));
		assertTrue("counters are cumulative, not a delta", json.contains("\"hitsplats\":2"));

		// Nothing has changed since, so there is nothing left to send.
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);

		// A new hit is a change, and the whole record goes again with its running totals.
		tick();
		recordHit(4);
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(2, transport.posts);
		assertTrue(transport.lastBodyAsJson().contains("\"hitsplats\":3"));
	}

	@Test
	public void aFailedUploadLeavesTheWatermarkAloneSoTheRecordsGoAgain() throws Exception
	{
		recordHit(10);
		transport.code = 500;

		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);
		assertNotNull(sync.getStatus());

		// The backoff holds the next attempt off, and the records are still pending.
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);
		assertNotNull(store.pendingUpload(10, "install", "client"));
	}

	@Test
	public void aRejectedBatchIsNotSentAgainUnchanged() throws Exception
	{
		recordHit(10);
		transport.code = 400;

		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);

		// A 400 sets no backoff: the payload, not the server, is the problem.
		transport.code = 200;
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals("the same records must not be sent again", 1, transport.posts);

		// Something new makes the batch different, so it is worth trying again.
		tick();
		recordHit(7);
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(2, transport.posts);
	}

	@Test
	public void anOversizedBatchHalvesTheCap() throws Exception
	{
		recordHit(10);
		transport.code = 413;

		await(sync.upload(CommunitySync.Reason.TIMER));

		assertEquals(1, transport.posts);
		assertTrue(sync.getStatus().contains("too large"));
	}

	@Test
	public void anOutdatedClientStopsUploading() throws Exception
	{
		recordHit(10);
		transport.code = 426;

		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);
		assertEquals("Community: update the plugin", sync.getStatus());

		tick();
		recordHit(3);
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals("nothing more is sent until the plugin is updated", 1, transport.posts);
	}

	@Test
	public void aBusyServerBacksOffAndStopsTrying() throws Exception
	{
		recordHit(10);
		transport.code = 503;

		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals(1, transport.posts);

		transport.code = 200;
		tick();
		recordHit(5);
		await(sync.upload(CommunitySync.Reason.TIMER));
		assertEquals("the kill switch holds", 1, transport.posts);
	}

	@Test
	public void aLogoutUploadStillMarksTheFileAfterTheStoreIsUnloaded() throws Exception
	{
		recordHit(10);

		// What the plugin does on logout: capture on this thread, then unload before the response.
		transport.block = true;
		final Future<?> pending = sync.upload(CommunitySync.Reason.LOGOUT);
		store.unload();
		transport.release();
		await(pending);

		assertEquals(1, transport.posts);

		// The watermark landed in Alice's own file, so logging back in does not resend it all.
		store.load("Alice");
		assertNull("nothing should be pending after a logout upload",
			store.pendingUpload(10, "install", "client"));
	}

	@Test
	public void anUploaderIdIsCreatedOnceAndSurvivesAReload() throws Exception
	{
		assertNull(store.getUploaderId());

		recordHit(10);
		await(sync.upload(CommunitySync.Reason.TIMER));

		final String id = store.getUploaderId();
		assertNotNull(id);

		store.save();
		store.unload();
		store.load("Alice");
		assertEquals(id, store.getUploaderId());
	}

	@Test
	public void queryUrlCarriesTheFilterAndNothingElse()
	{
		final HistoryFilter filter = new HistoryFilter("Corrupted Hunllef", null,
			Collections.singletonMap(CombatContext.WEAPON_SLOT, 4151), "Aggressive", Boolean.FALSE);

		final CommunityQuery query = new CommunityQuery(filter, Arrays.asList(9037, 9036),
			LevelMatch.BRACKET, 95, new int[]{99, 99, 1, 1}, "uploader-1");

		assertTrue(query.isAskable());
		assertEquals(URL + "/v1/distribution?npc=9036,9037&s3=4151&attack=Aggressive"
			+ "&protected=0&levels=bracket&level=95&epoch=current&me=uploader-1", query.toUrl(URL));
	}

	@Test
	public void aFilterWithoutAWeaponIsNotWorthAsking()
	{
		final HistoryFilter filter = new HistoryFilter("Corrupted Hunllef", null, null, null, null);

		assertFalse(new CommunityQuery(filter, Collections.singletonList(9036),
			LevelMatch.ANY, -1, null, null).isAskable());
	}

	@Test
	public void communityAnswersAreReadTheSameWayTheLocalOnesAre()
	{
		final Gson gson = new Gson();

		assertFalse(gson.fromJson("{\"ok\":true,\"players\":0,\"empty\":true}",
			CommunityAggregate.class).hasData());
		assertFalse(gson.fromJson("{\"ok\":true,\"tooBroad\":true}",
			CommunityAggregate.class).hasData());

		final CommunityAggregate real = gson.fromJson(
			"{\"ok\":true,\"players\":42,\"others\":41,\"includesYou\":true,"
				+ "\"counts\":[10,0,4,6],\"killCounts\":[0,0,1],\"attacks\":20,\"hitsplats\":20,"
				+ "\"splashes\":0,\"maxHits\":2,\"activeTicks\":100,\"wastedTicks\":10,"
				+ "\"epoch\":{\"id\":1,\"startDay\":20261001,\"note\":\"rebalance\",\"ready\":true}}",
			CommunityAggregate.class);

		assertTrue(real.hasData());
		assertEquals(41, real.getOthers());
		assertEquals(3, real.getHighestHit());
		// Four hits of 2 and six of 3 is 26 damage, plus one killing blow of 2.
		assertEquals(28, real.getTotalDamage());
		assertEquals(10, real.getZeroHits());
		assertEquals(0.5, real.getAccuracy(), 1e-9);
		assertNotNull(real.getEpoch());
		assertEquals("2026-10-01", real.getEpoch().getStartLabel());
	}

	@Test
	public void theCommunitySeriesFollowsTheKillingBlowToggle()
	{
		// Four 2s and six 3s landed, plus one killing blow of 2 and one of 5. The server always
		// sends the two histograms apart; the toggle decides whether they are drawn as one.
		final CommunityAggregate a = new Gson().fromJson(
			"{\"ok\":true,\"counts\":[10,0,4,6],\"killCounts\":[0,0,1,0,0,1],"
				+ "\"hitsplats\":22,\"maxHits\":3,\"killingBlows\":2,\"killingBlowMaxHits\":1,"
				+ "\"splashes\":0,\"attacks\":22,\"activeTicks\":100}",
			CommunityAggregate.class);

		a.setIncludeKillingBlows(true);
		assertArrayEquals(new int[]{10, 0, 5, 6, 0, 1}, a.getChartedCounts());
		assertEquals(22, a.getChartedHitsplats());
		assertEquals(3, a.getChartedMaxHits());
		assertEquals(5, a.getHighestHit());
		// 5*2 + 6*3 + 1*5 = 33 over 22 hitsplats.
		assertEquals(33.0 / 22, a.getAveragePerHitsplat(), 1e-9);

		a.setIncludeKillingBlows(false);
		assertArrayEquals(new int[]{10, 0, 4, 6}, a.getChartedCounts());
		assertEquals(20, a.getChartedHitsplats());
		assertEquals(2, a.getChartedMaxHits());
		assertEquals(3, a.getHighestHit());
		assertEquals(26.0 / 20, a.getAveragePerHitsplat(), 1e-9);

		// Damage dealt is damage dealt, whichever way the toggle is set.
		assertEquals(33, a.getTotalDamage());
	}

	// ------------------------------------------------------------------ helpers

	private void recordHit(int damage)
	{
		final CombatContext context = context();
		store.recordAttack(context, 0, 4);
		store.recordHit(context, damage, false);
	}

	/**
	 * The watermark is a millisecond timestamp, so a record touched in the same millisecond it was
	 * uploaded in would not look new. Nothing in the plugin uploads twice in one millisecond;
	 * these tests do.
	 */
	private static void tick() throws InterruptedException
	{
		Thread.sleep(2);
	}

	private static CombatContext context()
	{
		final int[] gear = new int[CombatContext.GEAR_SLOTS];
		Arrays.fill(gear, -1);
		gear[CombatContext.WEAPON_SLOT] = 4151;
		return AttackMatcherTest.builder(CombatStyle.MELEE, 4, 9036).gear(gear)
			.styleName("Aggressive").build();
	}

	private static void await(Future<?> future) throws Exception
	{
		future.get(5, TimeUnit.SECONDS);
	}

	/** Records what was posted and answers with whatever the test asked for. */
	private static class FakeTransport implements SyncTransport
	{
		private final Object gate = new Object();

		private int posts;
		private int code = 200;
		private volatile boolean block;

		@Nullable
		private byte[] lastBody;

		@Override
		public Response post(String url, byte[] body)
		{
			synchronized (gate)
			{
				while (block)
				{
					try
					{
						gate.wait(5000);
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
			posts++;
			lastBody = body;
			return new Response(code, code == 200 ? "{\"ok\":true}" : "{\"ok\":false}");
		}

		@Override
		public Response get(String url)
		{
			return new Response(code, "{\"ok\":true}");
		}

		void release()
		{
			synchronized (gate)
			{
				block = false;
				gate.notifyAll();
			}
		}

		String lastBodyAsJson() throws IOException
		{
			assertNotNull(lastBody);
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(lastBody)))
			{
				final byte[] buffer = new byte[4096];
				int read;
				while ((read = in.read(buffer)) > 0)
				{
					out.write(buffer, 0, read);
				}
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/** Only the settings the upload reads; the rest keep their interface defaults. */
	private static class FakeConfig implements HitStatsConfig
	{
		private boolean uploadEnabled = true;
		private String serverUrl = URL;

		@Override
		public boolean uploadEnabled()
		{
			return uploadEnabled;
		}

		@Override
		public String serverUrl()
		{
			return serverUrl;
		}

		@Override
		public String installId()
		{
			// Non-empty, so the upload never reaches ConfigManager, which is null in these tests.
			return "install-under-test";
		}
	}
}
