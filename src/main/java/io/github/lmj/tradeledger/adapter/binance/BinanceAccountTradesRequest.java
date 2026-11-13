package io.github.lmj.tradeledger.adapter.binance;

import java.nio.charset.StandardCharsets;

/**
 * Immutable input for one Spot account trade query for a single symbol.
 * Only symbol, limit, optional orderId, inclusive fromId and time bounds are retained, without credentials.
 * orderId alone or with fromId is supported; neither ID can mix with time bounds. No automatic pagination.
 * orderId, fromId and time bounds follow project policy: canonical ASCII decimal, 0 through Long.MAX_VALUE.
 * Time bounds support milliseconds only; two bounds must be ordered and at most 24 hours apart.
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
	private final Long fromId;
	private final Long startTime;
	private final Long endTime;
	private final Long orderId;

	public BinanceAccountTradesRequest(String symbol) {
		this(symbol, 500);
	}

	public BinanceAccountTradesRequest(String symbol, int limit) {
		this(symbol, limit, null);
	}

	public BinanceAccountTradesRequest(String symbol, int limit, String fromId) {
		this(symbol, limit, fromId, null, null);
	}

	public BinanceAccountTradesRequest(String symbol, int limit, String fromId, String startTime, String endTime) {
		this(symbol, limit, fromId, startTime, endTime, null);
	}

	public BinanceAccountTradesRequest(String symbol, int limit, String fromId, String startTime, String endTime, String orderId) {
		if (!validSymbol(symbol) || limit < 1 || limit > 1000) {
			throw invalid();
		}
		this.symbol = symbol;
		this.limit = limit;
		this.fromId = parseBound(fromId);
		this.startTime = parseBound(startTime);
		this.endTime = parseBound(endTime);
		this.orderId = parseBound(orderId);
		// Ordered nonnegative int64 values make subtraction safe, even near Long.MAX_VALUE.
		if (((this.fromId != null || this.orderId != null) && (this.startTime != null || this.endTime != null))
				|| (this.startTime != null && this.endTime != null
						&& (this.startTime > this.endTime || this.endTime - this.startTime > 86_400_000L))) {
			throw invalid();
		}
	}

	public String symbol() {
		return symbol;
	}

	public int limit() {
		return limit;
	}

	public Long fromId() {
		return fromId;
	}

	public Long startTime() {
		return startTime;
	}

	public Long endTime() {
		return endTime;
	}

	public Long orderId() {
		return orderId;
	}

	private static Long parseBound(String value) {
		if (value == null) {
			return null;
		}
		// Bound work before grammar checks and integer parsing; never use floating point.
		if (value.isEmpty() || value.length() > 19 || !value.matches("0|[1-9][0-9]*")) {
			throw invalid();
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	/**
	 * Returns the exact UTF-8 percent-encoded query to sign, without a leading '?'
	 * or signature. The transport must append {@code &signature=} directly
	 * to this returned string, without re-encoding it. The caller supplies a
	 * nonnegative epoch-millisecond timestamp; no clock or floating point is used.
	 */
	public String toQueryString(long timestamp) {
		if (timestamp < 0) {
			throw invalid();
		}
		return "symbol=" + encode(symbol) + "&limit=" + limit
				+ (fromId == null ? "" : "&fromId=" + fromId)
				+ (startTime == null ? "" : "&startTime=" + startTime)
				+ (endTime == null ? "" : "&endTime=" + endTime)
				+ (orderId == null ? "" : "&orderId=" + orderId)
				+ "&recvWindow=5000&timestamp=" + timestamp;
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
