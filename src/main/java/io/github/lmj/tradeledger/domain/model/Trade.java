package io.github.lmj.tradeledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * An immutable, fully closed trade used by the first journal format.
 */
public record Trade(
		String tradeId,
		Symbol symbol,
		Market market,
		Direction direction,
		BigDecimal quantity,
		Money entryPrice,
		Money exitPrice,
		Money fees,
		Instant openedAt,
		Instant closedAt,
		String strategy) {

	public Trade {
		tradeId = requireText(tradeId, "tradeId");
		Objects.requireNonNull(symbol, "symbol must not be null");
		Objects.requireNonNull(market, "market must not be null");
		Objects.requireNonNull(direction, "direction must not be null");
		Objects.requireNonNull(quantity, "quantity must not be null");
		Objects.requireNonNull(entryPrice, "entry price must not be null");
		Objects.requireNonNull(exitPrice, "exit price must not be null");
		Objects.requireNonNull(fees, "fees must not be null");
		Objects.requireNonNull(openedAt, "openedAt must not be null");
		Objects.requireNonNull(closedAt, "closedAt must not be null");
		strategy = requireText(strategy, "strategy");

		quantity = normalize(quantity);
		if (quantity.signum() <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
		if (entryPrice.amount().signum() <= 0) {
			throw new IllegalArgumentException("entry price must be positive");
		}
		if (exitPrice.amount().signum() <= 0) {
			throw new IllegalArgumentException("exit price must be positive");
		}
		if (fees.amount().signum() < 0) {
			throw new IllegalArgumentException("fees must be zero or positive");
		}
		if (!entryPrice.currency().equals(exitPrice.currency())
				|| !entryPrice.currency().equals(fees.currency())) {
			throw new IllegalArgumentException("entry price, exit price, and fees must use the same currency");
		}
		if (!closedAt.isAfter(openedAt)) {
			throw new IllegalArgumentException("closedAt must be after openedAt");
		}
	}

	private static String requireText(String value, String fieldName) {
		Objects.requireNonNull(value, fieldName + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return trimmed;
	}

	private static BigDecimal normalize(BigDecimal value) {
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		return value.stripTrailingZeros();
	}
}
