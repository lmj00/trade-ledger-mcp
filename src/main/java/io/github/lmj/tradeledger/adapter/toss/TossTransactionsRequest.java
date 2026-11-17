package io.github.lmj.tradeledger.adapter.toss;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Pure parameters for one all-types page (transactionTypes is omitted).
 * Independent, inclusive KST settlement-date bounds; unsettled rows use expected
 * settlement dates. Missing bounds remain absent, including future upper bounds.
 */
public record TossTransactionsRequest(String from, String to, String cursor, Integer limit) {

	public static final int MAX_STRING_LENGTH = 256;
	public static final int DEFAULT_LIMIT = 20;
	public static final int MAX_LIMIT = 100;

	public TossTransactionsRequest() {
		this(null, null, null, null);
	}

	public TossTransactionsRequest {
		if (cursor != null && !validCursor(cursor)) {
			throw invalid();
		}
		limit = limit == null ? DEFAULT_LIMIT : limit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw invalid();
		}
		LocalDate start = from == null ? null : date(from);
		LocalDate end = to == null ? null : date(to);
		if (start != null && end != null && start.isAfter(end)) {
			throw invalid();
		}
	}

	private static LocalDate date(String value) {
		if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
			throw invalid();
		}
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeException exception) {
			throw invalid();
		}
	}

	private static boolean validCursor(String value) {
		if (value.isBlank() || value.length() > MAX_STRING_LENGTH
				|| value.chars().anyMatch(Character::isISOControl)) {
			return false;
		}
		// Opaque token: preserve whitespace, punctuation and valid Unicode verbatim.
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isLowSurrogate(c) || (Character.isHighSurrogate(c)
					&& (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))))) {
				return false;
			}
		}
		return true;
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Toss transactions request", null);
	}
}
