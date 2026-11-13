package io.github.lmj.tradeledger.adapter.binance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Package-local, stateless HMAC-SHA256 signing of an already percent-encoded
 * ASCII query. The exact supplied bytes are signed, without parsing, sorting,
 * decoding or re-encoding. The secret is literal UTF-8 text, never Base64-decoded;
 * only lowercase hexadecimal is returned. Keys and queries are never retained
 * in fields or logged, and failures discard provider diagnostics and causes.
 * HTTP calls, MCP registration and live-account integration are not implemented.
 *
 * @see <a href="https://developers.binance.com/en/docs/products/spot/rest-api#hmac-keys">Official HMAC instructions and ASCII/percent-encoded non-ASCII test vectors</a>
 */
final class BinanceQuerySigner {
	private BinanceQuerySigner() {
	}

	/** Signs the query that a future transport must reuse verbatim before signature. */
	static String sign(String encodedQuery, String secretKey) {
		byte[] keyBytes = null;
		try {
			if (!validQuery(encodedQuery) || !validKey(secretKey)) {
				throw new IllegalArgumentException();
			}
			keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(encodedQuery.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException | RuntimeException exception) {
			// Never expose a provider's message, cause or suppressed exceptions.
			throw new IllegalArgumentException("Unable to sign Binance query");
		}
		finally {
			if (keyBytes != null) {
				Arrays.fill(keyBytes, (byte) 0);
			}
		}
	}

	private static boolean validQuery(String query) {
		if (query == null || query.isEmpty()) {
			return false;
		}
		for (int i = 0; i < query.length(); i++) {
			char character = query.charAt(i);
			if (character <= ' ' || character > '~' || character == '#') {
				return false;
			}
			if (character == '%') {
				if (i + 2 >= query.length() || !hex(query.charAt(i + 1)) || !hex(query.charAt(i + 2))) {
					return false;
				}
				i += 2;
			}
		}
		return true;
	}

	private static boolean hex(char character) {
		return (character >= '0' && character <= '9') || (character >= 'A' && character <= 'F')
				|| (character >= 'a' && character <= 'f');
	}

	private static boolean validKey(String key) {
		if (key == null || key.isBlank()) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char character = key.charAt(i);
			if (Character.isLowSurrogate(character)) {
				return false;
			}
			if (Character.isHighSurrogate(character)
					&& (++i == key.length() || !Character.isLowSurrogate(key.charAt(i)))) {
				return false;
			}
		}
		return true;
	}
}
