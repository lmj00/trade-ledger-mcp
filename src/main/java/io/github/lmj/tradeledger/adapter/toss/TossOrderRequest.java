package io.github.lmj.tradeledger.adapter.toss;

/** Pure single-order lookup parameters. The 256 UTF-16 unit cap is project policy. */
public record TossOrderRequest(String orderId) {

	public static final int MAX_ORDER_ID_LENGTH = 256;

	public TossOrderRequest {
		if (orderId == null || orderId.isBlank() || orderId.length() > MAX_ORDER_ID_LENGTH
				|| orderId.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Invalid Toss order request");
		}
		// Opaque identifier: do not trim, decode, normalize or restrict to sample tokens.
	}
}
