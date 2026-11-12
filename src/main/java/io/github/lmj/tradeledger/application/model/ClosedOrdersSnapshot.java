package io.github.lmj.tradeledger.application.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable order summaries, without fills, completed trades or PnL. Amounts are
 * decimal strings preserving scale; paidFee is the unallocated order-level fee.
 * Even a short or empty result does not establish complete history collection.
 */
public record ClosedOrdersSnapshot(String serviceId, List<Order> orders, boolean possiblyTruncated) {
	public ClosedOrdersSnapshot {
		Objects.requireNonNull(serviceId);
		orders = List.copyOf(orders);
	}

	public record Order(String orderId, String market, String side, String orderType, String state,
			Instant createdAt, String executedVolume, String paidFee, BigInteger tradesCount) {
		public Order {
			Objects.requireNonNull(orderId);
			Objects.requireNonNull(market);
			Objects.requireNonNull(side);
			Objects.requireNonNull(orderType);
			Objects.requireNonNull(state);
			Objects.requireNonNull(createdAt);
			Objects.requireNonNull(executedVolume);
			Objects.requireNonNull(paidFee);
			Objects.requireNonNull(tradesCount);
		}

		/** The summary provides no evidence of the fee currency. */
		public Optional<String> feeCurrency() {
			return Optional.empty();
		}
	}
}
