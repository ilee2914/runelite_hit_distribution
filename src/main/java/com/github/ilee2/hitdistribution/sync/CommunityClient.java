package com.github.ilee2.hitdistribution.sync;

import com.github.ilee2.hitdistribution.HitDistributionConfig;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches community aggregates and keeps the answers for the session.
 *
 * <p>The panel asks for an answer and gets whatever is already cached, immediately; if that is
 * nothing or it has gone stale, a fetch is started on the executor and the panel is told to
 * repaint when it lands. Nothing here ever blocks the Swing thread or the client thread, so the
 * game cannot tell whether the server is up.
 *
 * <p>Answers are not refreshed more than once every {@link #REFRESH_MS}, which matches how often
 * the server rebuilds them. Asking sooner would return the same numbers.
 */
@Slf4j
@Singleton
public class CommunityClient
{
	private static final long REFRESH_MS = 5L * 60 * 1000;

	/** How long a lookup that failed is left alone for. */
	private static final long RETRY_MS = 5L * 60 * 1000;

	/** Stop the session cache growing without bound if someone sweeps every filter. */
	private static final int MAX_ENTRIES = 200;

	private final CommunitySync sync;
	private final HitDistributionConfig config;
	private final SyncTransport transport;
	private final ScheduledExecutorService executor;
	private final Gson gson;

	private final Map<String, Entry> answers = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	/**
	 * When a lookup that failed may be tried again. Without this a failing server would be asked
	 * again on every panel refresh, which is every two seconds.
	 */
	private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();

	/** Bumped whenever an answer lands, so the panel knows it has something new to draw. */
	@Getter
	private volatile long revision;

	@Inject
	public CommunityClient(CommunitySync sync, HitDistributionConfig config, SyncTransport transport,
		ScheduledExecutorService executor, Gson gson)
	{
		this.sync = sync;
		this.config = config;
		this.transport = transport;
		this.executor = executor;
		this.gson = gson;
	}

	/**
	 * @return the community answer for {@code query} if one is known, else null while a fetch runs.
	 * Starts that fetch when there is nothing cached or the answer has aged out.
	 */
	@Nullable
	public CommunityAggregate get(CommunityQuery query)
	{
		if (!config.showCommunity() || !query.isAskable() || sync.baseUrl().isEmpty())
		{
			return null;
		}

		final String key = query.cacheKey();
		final Entry cached = answers.get(key);
		if (cached == null || System.currentTimeMillis() - cached.fetchedAt > REFRESH_MS)
		{
			fetch(key, query);
		}
		if (cached == null)
		{
			return null;
		}
		cached.aggregate.setIncludeKillingBlows(config.includeKillingBlows());
		return cached.aggregate;
	}

	/** Whether a fetch for this lookup is running, so the panel can say "loading" rather than nothing. */
	public boolean isLoading(CommunityQuery query)
	{
		return inFlight.contains(query.cacheKey());
	}

	private void fetch(String key, CommunityQuery query)
	{
		final Long blockedUntil = retryAfter.get(key);
		if (blockedUntil != null && System.currentTimeMillis() < blockedUntil)
		{
			return;
		}
		if (!inFlight.add(key))
		{
			return;
		}

		final String url = query.toUrl(sync.baseUrl());
		executor.execute(() ->
		{
			try
			{
				final SyncTransport.Response response = transport.get(url);
				if (!response.isOk())
				{
					log.debug("Community query {} returned {}", key, response.getCode());
					retryAfter.put(key, System.currentTimeMillis() + RETRY_MS);
					return;
				}

				final CommunityAggregate parsed = gson.fromJson(response.getBody(), CommunityAggregate.class);
				if (parsed == null)
				{
					retryAfter.put(key, System.currentTimeMillis() + RETRY_MS);
					return;
				}
				retryAfter.remove(key);
				if (answers.size() >= MAX_ENTRIES)
				{
					answers.clear();
					retryAfter.clear();
				}
				answers.put(key, new Entry(parsed, System.currentTimeMillis()));
				revision++;
			}
			catch (IOException | RuntimeException e)
			{
				// A community comparison is a nicety. Losing one is not worth telling anyone about,
				// but it must not turn into a request every two seconds.
				retryAfter.put(key, System.currentTimeMillis() + RETRY_MS);
				log.debug("Community query failed", e);
			}
			finally
			{
				inFlight.remove(key);
			}
		});
	}

	public void clear()
	{
		answers.clear();
		inFlight.clear();
		retryAfter.clear();
		revision++;
	}

	private static class Entry
	{
		private final CommunityAggregate aggregate;
		private final long fetchedAt;

		Entry(CommunityAggregate aggregate, long fetchedAt)
		{
			this.aggregate = aggregate;
			this.fetchedAt = fetchedAt;
		}
	}
}
