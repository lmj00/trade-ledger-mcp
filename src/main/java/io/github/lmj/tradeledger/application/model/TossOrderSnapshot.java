package io.github.lmj.tradeledger.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Immutable order and cumulative fill summary, not individual fills or a Trade.
 * Decimal strings retain source precision, trailing zeros and nulls. Commission
 * and tax are separate native-currency totals; no arithmetic or sign is inferred.
 * Optional source fields collapse omission and explicit null to Java null.
 */
public record TossOrderSnapshot(
		String orderId,
		String symbol,
		String side,
		String orderType,
		String timeInForce,
		String status,
		String price,
		String quantity,
		String orderAmount,
		String currency,
		Instant orderedAt,
		Instant canceledAt,
		ExecutionSummary execution) {

	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_DECIMAL_LENGTH = 30;
	private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");

	public TossOrderSnapshot {
		requireText(orderId);
		requireText(symbol);
		requireText(side);
		requireText(orderType);
		requireText(timeInForce);
		requireText(status);
		requireText(currency);
		require(side.equals("BUY") || side.equals("SELL"));
		requireDecimal(price, true);
		requireDecimal(quantity, false);
		requireDecimal(orderAmount, true);
		require(orderedAt != null && execution != null);
	}

	/** filledAt is the final fill time; settlementDate is a date, not a settlement ledger. */
	public record ExecutionSummary(String filledQuantity, String averageFilledPrice,
			String filledAmount, String commission, String tax, Instant filledAt, LocalDate settlementDate) {

		public ExecutionSummary {
			requireDecimal(filledQuantity, false);
			requireDecimal(averageFilledPrice, true);
			requireDecimal(filledAmount, true);
			requireDecimal(commission, true);
			requireDecimal(tax, true);
		}
	}

	private static void requireText(String value) {
		require(value != null && !value.isBlank() && value.length() <= MAX_STRING_LENGTH
				&& value.chars().noneMatch(Character::isISOControl));
	}

	private static void requireDecimal(String value, boolean nullable) {
		require(value == null ? nullable
				: value.length() <= MAX_DECIMAL_LENGTH && DECIMAL.matcher(value).matches());
	}

	private static void require(boolean valid) {
		if (!valid) {
			throw new IllegalArgumentException("Invalid Toss order summary");
		}
	}
}
