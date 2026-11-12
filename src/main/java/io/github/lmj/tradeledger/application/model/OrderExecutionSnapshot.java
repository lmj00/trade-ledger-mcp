package io.github.lmj.tradeledger.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only order and its actual fills, independent of closed trades and PnL.
 * Amounts are plain decimal strings, preserving precision and scale. paidFee is
 * the order's total fee; it is never allocated to individual fills.
 */
public record OrderExecutionSnapshot(
		String serviceId,
		String orderId,
		String market,
		String side,
		String orderType,
		String state,
		Instant createdAt,
		String executedVolume,
		String paidFee,
		List<Execution> trades) {

	public OrderExecutionSnapshot {
		Objects.requireNonNull(serviceId);
		Objects.requireNonNull(orderId);
		Objects.requireNonNull(market);
		Objects.requireNonNull(side);
		Objects.requireNonNull(orderType);
		Objects.requireNonNull(state);
		Objects.requireNonNull(createdAt);
		Objects.requireNonNull(executedVolume);
		Objects.requireNonNull(paidFee);
		trades = List.copyOf(trades);
	}

	/** The supplied order data does not establish the fee currency. */
	public Optional<String> feeCurrency() {
		return Optional.empty();
	}

	public record Execution(
			String tradeId,
			String market,
			String side,
			String price,
			String volume,
			String funds,
			Instant createdAt) {

		public Execution {
			Objects.requireNonNull(tradeId);
			Objects.requireNonNull(market);
			Objects.requireNonNull(side);
			Objects.requireNonNull(price);
			Objects.requireNonNull(volume);
			Objects.requireNonNull(funds);
			Objects.requireNonNull(createdAt);
		}
	}
}
