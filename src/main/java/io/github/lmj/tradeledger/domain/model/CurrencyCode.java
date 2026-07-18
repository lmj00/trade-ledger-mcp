package io.github.lmj.tradeledger.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Currency identifier used by the ledger.
 *
 * <p>Unlike {@link java.util.Currency}, this type also accepts non-ISO codes
 * used by crypto markets, such as {@code USDT}.</p>
 */
public record CurrencyCode(String value) {

	private static final Pattern VALID_CODE = Pattern.compile("[A-Z0-9]{2,10}");

	public CurrencyCode {
		Objects.requireNonNull(value, "currency code must not be null");
		value = value.trim().toUpperCase(Locale.ROOT);
		if (!VALID_CODE.matcher(value).matches()) {
			throw new IllegalArgumentException(
					"currency code must contain 2 to 10 uppercase letters or digits");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
