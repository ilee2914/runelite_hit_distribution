package com.github.ilee2.hitstats.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * {@link SyncTransport} over the client's own HTTP stack. RuneLite injects a configured
 * {@link OkHttpClient}; this takes a builder from it so the connection pool and proxy settings are
 * shared, and only the timeouts differ. A slow server must never hold an executor thread for long,
 * so both are short.
 */
@Singleton
public class OkHttpTransport implements SyncTransport
{
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final int CONNECT_TIMEOUT_SECONDS = 5;
	private static final int READ_TIMEOUT_SECONDS = 10;

	private final OkHttpClient client;
	private final String userAgent;

	@Inject
	public OkHttpTransport(OkHttpClient shared)
	{
		this.client = shared.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
		this.userAgent = "hit-stats/" + version();
	}

	@Override
	public SyncTransport.Response post(String url, byte[] body) throws IOException
	{
		return send(new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.header("Content-Encoding", "gzip")
			.post(RequestBody.create(JSON, body))
			.build());
	}

	@Override
	public SyncTransport.Response get(String url) throws IOException
	{
		return send(new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.get()
			.build());
	}

	private SyncTransport.Response send(Request request) throws IOException
	{
		try (okhttp3.Response response = client.newCall(request).execute())
		{
			final ResponseBody body = response.body();
			return new SyncTransport.Response(response.code(), body == null ? "" : body.string());
		}
	}

	/** Gzips {@code json} for {@link #post}. OkHttp does not compress request bodies itself. */
	public static byte[] gzip(byte[] json) throws IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, json.length / 4));
		try (OutputStream gz = new GZIPOutputStream(out))
		{
			gz.write(json);
		}
		return out.toByteArray();
	}

	/** The plugin version, for the User-Agent; only a packaged jar carries one. */
	private static String version()
	{
		final Package pkg = OkHttpTransport.class.getPackage();
		final String implementation = pkg == null ? null : pkg.getImplementationVersion();
		return implementation == null ? "dev" : implementation;
	}
}
