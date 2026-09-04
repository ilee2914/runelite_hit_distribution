package com.github.ilee2.hitstats.sync;

import java.io.IOException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The two HTTP calls this plugin makes, behind an interface so the upload and query logic can be
 * tested without a network or an extra test dependency. The real implementation is
 * {@link OkHttpTransport}.
 */
public interface SyncTransport
{
	/** @param body already gzipped JSON; the implementation sets {@code Content-Encoding}. */
	Response post(String url, byte[] body) throws IOException;

	Response get(String url) throws IOException;

	@Getter
	@RequiredArgsConstructor
	class Response
	{
		private final int code;
		private final String body;

		public boolean isOk()
		{
			return code >= 200 && code < 300;
		}
	}
}
