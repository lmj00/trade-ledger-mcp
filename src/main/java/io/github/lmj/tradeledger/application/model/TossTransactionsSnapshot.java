package io.github.lmj.tradeledger.application.model;

import java.util.List;

/** One immutable page in source order, including equal rows; no history completeness claim. */
public record TossTransactionsSnapshot(List<TossTransactionSnapshot> transactions, String nextCursor, boolean hasNext) {

	public static final int MAX_TRANSACTIONS = 100;
	public static final int MAX_CURSOR_LENGTH = 256;

	public TossTransactionsSnapshot {
		if (transactions == null || transactions.size() > MAX_TRANSACTIONS
				|| (hasNext ? nextCursor == null || !validCursor(nextCursor) : nextCursor != null)) {
			throw invalid();
		}
		for (TossTransactionSnapshot transaction : transactions) {
			if (transaction == null) {
				throw invalid();
			}
		}
		transactions = List.copyOf(transactions);
	}

	private static boolean validCursor(String value) {
		if (value.isBlank() || value.length() > MAX_CURSOR_LENGTH
				|| value.chars().anyMatch(Character::isISOControl)) {
			return false;
		}
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
		return new IllegalArgumentException("Invalid Toss transactions summary", null);
	}
}
