package io.github.lmj.tradeledger.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable monetary amount with an explicit currency.
 */
public record Money(BigDecimal amount, CurrencyCode currency) {

	public Money {
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		amount = normalize(amount);
	}

	public static Money of(String amount, String currency) {
		return new Money(new BigDecimal(amount), new CurrencyCode(currency));
	}

	public Money add(Money other) {
		requireSameCurrency(other);
		return new Money(amount.add(other.amount), currency);
	}

	public Money subtract(Money other) {
		requireSameCurrency(other);
		return new Money(amount.subtract(other.amount), currency);
	}

	public Money multiply(BigDecimal multiplier) {
		Objects.requireNonNull(multiplier, "multiplier must not be null");
		return new Money(amount.multiply(multiplier), currency);
	}

	private void requireSameCurrency(Money other) {
		Objects.requireNonNull(other, "other money must not be null");
		if (!currency.equals(other.currency)) {
			throw new IllegalArgumentException(
					"currency mismatch: %s and %s".formatted(currency, other.currency));
		}
	}

	private static BigDecimal normalize(BigDecimal value) {
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		return value.stripTrailingZeros();
	}
}
