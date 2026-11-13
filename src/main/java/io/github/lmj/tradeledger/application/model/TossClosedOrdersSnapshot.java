package io.github.lmj.tradeledger.application.model;

import java.util.HashSet;
import java.util.List;

/** One immutable page, in source order. Metadata does not imply complete history. */
public record TossClosedOrdersSnapshot(List<TossOrderSnapshot> orders, String nextCursor, boolean hasNext) {

	public static final int MAX_ORDERS = 100;
	public static final int MAX_CURSOR_LENGTH = 256;

	public TossClosedOrdersSnapshot {
		if (orders == null || orders.size() > MAX_ORDERS
				|| (hasNext ? nextCursor == null || !validCursor(nextCursor) : nextCursor != null)) {
			throw invalid();
		}
		var ids = new HashSet<String>();
		for (TossOrderSnapshot order : orders) {
			if (order == null || !ids.add(order.orderId())) {
				throw invalid();
			}
		}
		orders = List.copyOf(orders);
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
		return new IllegalArgumentException("Invalid Toss closed orders summary");
	}
}
