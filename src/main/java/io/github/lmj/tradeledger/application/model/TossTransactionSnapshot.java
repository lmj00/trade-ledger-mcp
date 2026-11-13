package io.github.lmj.tradeledger.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * One settlement-ledger row, not an individual fill, order or Trade.
 * Decimal strings retain precision, trailing zeros and explicit nulls. Costs and
 * netAmount remain in currency; no arithmetic, cash direction or linkage is inferred.
 * settled is authoritative even when the expected settlement date is in the past.
 */
public record TossTransactionSnapshot(
		String transactionType,
		String marketCountry,
		String currency,
		String symbol,
		String assetClass,
		String isinCode,
		String quantity,
		String filledPrice,
		String amount,
		String commission,
		String tax,
		String netAmount,
		String exchangeRate,
		LocalDate settlementDate,
		boolean settled,
		Instant transactionTime,
		boolean isCancellation) {

	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_DECIMAL_LENGTH = 30;
	// Project policy: ordinary nonnegative decimal notation, without signs/exponents.
	private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

	public TossTransactionSnapshot {
		requireText(transactionType, false);
		requireText(marketCountry, false);
		requireText(currency, false);
		requireText(symbol, true);
		requireText(assetClass, true);
		requireText(isinCode, true);
		requireDecimal(quantity, true);
		requireDecimal(filledPrice, true);
		requireDecimal(amount, true);
		requireDecimal(commission, false);
		requireDecimal(tax, false);
		requireDecimal(netAmount, true);
		requireDecimal(exchangeRate, true);
		require(settlementDate != null);
	}

	private static void requireText(String value, boolean nullable) {
		if (value == null) {
			require(nullable);
			return;
		}
		require(!value.isBlank() && value.length() <= MAX_STRING_LENGTH
				&& value.chars().noneMatch(Character::isISOControl));
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			require(!Character.isLowSurrogate(c) && (!Character.isHighSurrogate(c)
					|| (++i < value.length() && Character.isLowSurrogate(value.charAt(i)))));
		}
	}

	private static void requireDecimal(String value, boolean nullable) {
		require(value == null ? nullable
				: value.length() <= MAX_DECIMAL_LENGTH && DECIMAL.matcher(value).matches());
	}

	private static void require(boolean valid) {
		if (!valid) {
			throw new IllegalArgumentException("Invalid Toss transaction summary", null);
		}
	}
}
