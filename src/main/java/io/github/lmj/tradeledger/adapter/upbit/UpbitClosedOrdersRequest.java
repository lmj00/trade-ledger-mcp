package io.github.lmj.tradeledger.adapter.upbit;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parameters only: no network calls or pagination. This initial implementation
 * requires both time boundaries or neither. Omitted boundaries remain absent so
 * Upbit supplies its recent-seven-day default; no local clock is consulted.
 * Boundaries accept offset ISO8601 or signed integer epoch milliseconds and are
 * normalized to UTC ISO8601. A full result never establishes complete history.
 */
public record UpbitClosedOrdersRequest(String market, String state, List<String> states,
		String startTime, String endTime, Integer limit, String orderBy) {

	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_LIMIT = 1_000;
	private static final Pattern MARKET = Pattern.compile("[A-Z0-9]+-[A-Z0-9]+");
	private static final Pattern MILLIS = Pattern.compile("[+-]?[0-9]+");

	public UpbitClosedOrdersRequest() {
		this(null, null, null, null, null, null, null);
	}

	public UpbitClosedOrdersRequest {
		if (market != null && (market.length() > MAX_STRING_LENGTH || !MARKET.matcher(market).matches())) {
			throw invalid();
		}
		if (state != null && states != null) {
			throw invalid();
		}
		if (state != null && !closedState(state)) {
			throw invalid();
		}
		if (states != null) {
			if (states.isEmpty() || states.size() > 2 || states.stream().anyMatch(s -> !closedState(s))
					|| new HashSet<>(states).size() != states.size()) {
				throw invalid();
			}
			states = List.copyOf(states);
		}
		else if (state == null) {
			states = List.of("done", "cancel");
		}
		limit = limit == null ? 100 : limit;
		orderBy = orderBy == null ? "desc" : orderBy;
		if (limit < 1 || limit > MAX_LIMIT || !(orderBy.equals("asc") || orderBy.equals("desc"))) {
			throw invalid();
		}
		if ((startTime == null) != (endTime == null)) {
			throw invalid();
		}
		if (startTime != null) {
			Instant start = parseTime(startTime);
			Instant end = parseTime(endTime);
			if (start.isAfter(end) || Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0) {
				throw invalid();
			}
			startTime = start.toString();
			endTime = end.toString();
		}
	}

	private static boolean closedState(String value) {
		return "done".equals(value) || "cancel".equals(value);
	}

	private static Instant parseTime(String value) {
		if (value.length() > MAX_STRING_LENGTH) {
			throw invalid();
		}
		try {
			return MILLIS.matcher(value).matches() ? Instant.ofEpochMilli(Long.parseLong(value))
					: OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeException | NumberFormatException exception) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Upbit closed orders request");
	}
}
