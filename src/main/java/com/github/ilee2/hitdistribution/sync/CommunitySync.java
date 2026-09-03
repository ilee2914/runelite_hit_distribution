package com.github.ilee2.hitdistribution.sync;

import com.github.ilee2.hitdistribution.HitDistributionConfig;
import com.github.ilee2.hitdistribution.HitDistributionStore;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Sends what has been recorded to the community server, when the player has opted in.
 *
 * <p>Nothing here is on the path of an attack. The upload runs on the plugin's executor, at most
 * one at a time, on the same occasions the history file is written: a timer, logging out, closing
 * the client, and once after logging in to catch up whatever a crash interrupted. A batch carries
 * cumulative counters, so every failure mode ends in the same place: send it again and the server
 * ignores what it already has.
 */
@Slf4j
@Singleton
public class CommunitySync
{
	/**
	 * Where the Worker lives. **Empty until it is deployed**: the owner picks the workers.dev
	 * subdomain, and until this holds a real URL the plugin will not talk to anything, whatever
	 * the config says. See section 9 of the server plan.
	 */
	static final String DEFAULT_BASE_URL = "";

	static final String SYNC_PATH = "/v1/sync";

	/** Contexts per upload. Halved for the session if the server says a batch was too large. */
	private static final int MAX_BATCH = 2000;
	private static final int MIN_BATCH = 50;

	private static final long BASE_BACKOFF_MS = 5L * 60 * 1000;
	private static final long MAX_BACKOFF_MS = 60L * 60 * 1000;
	private static final long DAY_MS = 24L * 60 * 60 * 1000;

	private static final int HTTP_BAD_REQUEST = 400;
	private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
	private static final int HTTP_UPGRADE_REQUIRED = 426;
	private static final int HTTP_TOO_MANY_REQUESTS = 429;
	private static final int HTTP_UNAVAILABLE = 503;

	public enum Reason
	{
		TIMER, LOGIN, LOGOUT, SHUTDOWN
	}

	private final HitDistributionStore store;
	private final HitDistributionConfig config;
	private final ConfigManager configManager;
	private final SyncTransport transport;
	private final ScheduledExecutorService executor;
	private final Gson gson;

	private final AtomicBoolean inFlight = new AtomicBoolean();

	private volatile int batchCap = MAX_BATCH;
	private volatile long nextAttemptAt;
	private volatile long backoffMs;
	private volatile long lastSuccessAt;
	private volatile long retiredUntil;

	/** Watermark of a batch the server rejected as malformed; not worth sending again unchanged. */
	private volatile long rejectedThrough = -1;

	@Nullable
	private volatile String failure;

	@Inject
	public CommunitySync(HitDistributionStore store, HitDistributionConfig config,
		ConfigManager configManager, SyncTransport transport, ScheduledExecutorService executor,
		Gson gson)
	{
		this.store = store;
		this.config = config;
		this.configManager = configManager;
		this.transport = transport;
		this.executor = executor;
		this.gson = gson;
	}

	/**
	 * Sends anything outstanding, unless uploads are off, the server is unreachable for now, or a
	 * request is already running.
	 *
	 * <p>The batch is captured on the calling thread and only the HTTP call is handed to the
	 * executor. That matters on logout: the store is unloaded moments later, and a capture that
	 * ran afterwards would find nothing.
	 *
	 * @return a future that completes when the request is done, so closing the client can wait
	 * briefly for it; already complete when there was nothing to send.
	 */
	public Future<?> upload(Reason reason)
	{
		if (!config.uploadEnabled() || baseUrl().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		final long now = System.currentTimeMillis();
		if (now < retiredUntil || now < nextAttemptAt)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (!inFlight.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(null);
		}

		final UploadBatch batch;
		try
		{
			batch = store.pendingUpload(batchCap, installId(), clientVersion());
		}
		catch (RuntimeException e)
		{
			inFlight.set(false);
			log.debug("Unable to build a community upload", e);
			return CompletableFuture.completedFuture(null);
		}

		if (batch == null || batch.getThrough() == rejectedThrough)
		{
			// Nothing new, or the same records the server already called malformed.
			inFlight.set(false);
			return CompletableFuture.completedFuture(null);
		}

		return executor.submit(() ->
		{
			try
			{
				final byte[] body = OkHttpTransport.gzip(
					gson.toJson(batch).getBytes(StandardCharsets.UTF_8));
				log.debug("Community upload: {} contexts, {} bytes, {}", batch.size(), body.length, reason);
				onResponse(transport.post(baseUrl() + SYNC_PATH, body), batch);
			}
			catch (IOException | RuntimeException e)
			{
				// Never let an upload escape into the client. The watermark is untouched, so the
				// same records go again next time.
				failure = "upload failed";
				backOff(BASE_BACKOFF_MS);
				log.debug("Community upload failed", e);
			}
			finally
			{
				inFlight.set(false);
			}
		});
	}

	/** The whole failure policy, in one place. */
	private void onResponse(SyncTransport.Response response, UploadBatch batch)
	{
		final long now = System.currentTimeMillis();
		if (response.isOk())
		{
			store.markUploaded(batch);
			lastSuccessAt = now;
			failure = null;
			backoffMs = 0;
			nextAttemptAt = 0;
			rejectedThrough = -1;
			return;
		}

		switch (response.getCode())
		{
			case HTTP_BAD_REQUEST:
				// Our own payload is wrong. Retrying it unchanged cannot help.
				rejectedThrough = batch.getThrough();
				failure = "server rejected the data";
				log.debug("Community upload rejected: {}", response.getBody());
				break;
			case HTTP_PAYLOAD_TOO_LARGE:
				batchCap = Math.max(MIN_BATCH, batchCap / 2);
				failure = "batch too large, sending less";
				backOff(BASE_BACKOFF_MS);
				break;
			case HTTP_UPGRADE_REQUIRED:
				retiredUntil = now + DAY_MS;
				failure = "update the plugin";
				log.info("The community server no longer accepts this version of Hit Distribution");
				break;
			case HTTP_TOO_MANY_REQUESTS:
			case HTTP_UNAVAILABLE:
				failure = "server busy";
				backOff(MAX_BACKOFF_MS);
				break;
			default:
				failure = "server error " + response.getCode();
				backOff(BASE_BACKOFF_MS);
				break;
		}
	}

	private void backOff(long floor)
	{
		backoffMs = Math.min(MAX_BACKOFF_MS, Math.max(floor, backoffMs * 2));
		nextAttemptAt = System.currentTimeMillis() + backoffMs;
	}

	/** @return the community base URL: the hidden development override, else the built-in one. */
	public String baseUrl()
	{
		final String override = config.serverUrl();
		final String url = override == null || override.trim().isEmpty() ? DEFAULT_BASE_URL : override.trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/**
	 * A random id for this client installation, created once. It is sent so the server can tell
	 * two clients apart in its logs; it is not tied to an account and is not what the data is
	 * stored under, which is the per-file uploader id.
	 */
	private String installId()
	{
		final String existing = config.installId();
		if (existing != null && !existing.isEmpty())
		{
			return existing;
		}
		final String created = UUID.randomUUID().toString();
		configManager.setConfiguration(HitDistributionConfig.GROUP, "installId", created);
		return created;
	}

	static String clientVersion()
	{
		final Package pkg = CommunitySync.class.getPackage();
		final String implementation = pkg == null ? null : pkg.getImplementationVersion();
		return "hit-distribution/" + (implementation == null ? "dev" : implementation);
	}

	/** One line for the bottom of the panel, or null when there is nothing worth saying. */
	@Nullable
	public String getStatus()
	{
		if (!config.uploadEnabled())
		{
			return null;
		}
		if (baseUrl().isEmpty())
		{
			return "Community: no server configured";
		}
		if (System.currentTimeMillis() < retiredUntil)
		{
			return "Community: update the plugin";
		}

		final String problem = failure;
		if (problem != null)
		{
			return "Community: " + problem + ", will retry";
		}
		if (lastSuccessAt == 0)
		{
			return "Community: nothing shared yet";
		}
		return "Community: shared " + ago(System.currentTimeMillis() - lastSuccessAt);
	}

	private static String ago(long millis)
	{
		final long minutes = millis / 60000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + " min ago";
		}
		return (minutes / 60) + " h ago";
	}
}
