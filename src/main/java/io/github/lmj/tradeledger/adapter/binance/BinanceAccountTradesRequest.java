package io.github.lmj.tradeledger.adapter.binance;

import java.nio.charset.StandardCharsets;

/**
 * Immutable input for one recent Spot account trade query for a single symbol.
 * Only symbol and limit are retained: no keys, signatures, account aliases or URLs.
 * Order IDs, from IDs, time filters and pagination are deliberately unsupported.
 * HTTP calls, MCP registration and live-account integration are not implemented.
 *
 * <p>Project policy, not Binance symbol grammar: symbol must be nonblank,
 * contain at most 256 UTF-16 code units (consistent with the response mapper),
 * and contain neither ISO control characters nor unpaired surrogates. Valid
 * non-ASCII characters, case and surrounding spaces are preserved without trim.
 *
 * @see <a href="https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account">Official account trade list: symbol, recent trades and limit 500/max 1000</a>
 * @see <a href="https://developers.binance.com/en/docs/products/spot/rest-api">Official signed requests: percent-encoding, timestamp and default recvWindow 5000</a>
 */
public final class BinanceAccountTradesRequest {
	private final String symbol;
	private final int limit;

	public BinanceAccountTradesRequest(String symbol) {
		this(symbol, 500);
	}

	public BinanceAccountTradesRequest(String symbol, int limit) {
		if (!validSymbol(symbol) || limit < 1 || limit > 1000) {
			throw invalid();
		}
		this.symbol = symbol;
		this.limit = limit;
	}

	public String symbol() {
		return symbol;
	}

	public int limit() {
		return limit;
	}

	/**
	 * Returns the exact UTF-8 percent-encoded query to sign, without a leading '?'
	 * or signature. A future transport must append {@code &signature=} directly
	 * to this returned string, without re-encoding it. The caller supplies a
	 * nonnegative epoch-millisecond timestamp; no clock or floating point is used.
	 */
	public String toQueryString(long timestamp) {
		if (timestamp < 0) {
			throw invalid();
		}
		return "symbol=" + encode(symbol) + "&limit=" + limit + "&recvWindow=5000&timestamp=" + timestamp;
	}

	private static boolean validSymbol(String value) {
		if (value == null || value.length() > 256 || value.isBlank()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (Character.isISOControl(character) || Character.isLowSurrogate(character)) {
				return false;
			}
			if (Character.isHighSurrogate(character)
					&& (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) {
				return false;
			}
		}
		return true;
	}

	private static String encode(String value) {
		StringBuilder encoded = new StringBuilder();
		for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
			int character = valueByte & 0xff;
			if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')
					|| (character >= '0' && character <= '9') || character == '-'
					|| character == '.' || character == '_' || character == '~') {
				encoded.append((char) character);
			}
			else {
				encoded.append('%').append("0123456789ABCDEF".charAt(character >>> 4))
						.append("0123456789ABCDEF".charAt(character & 15));
			}
		}
		return encoded.toString();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Binance account trades request");
	}
}
