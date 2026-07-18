package io.github.lmj.tradeledger.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Broker-independent market symbol, normalized to uppercase.
 */
public record Symbol(String value) {

	private static final Pattern VALID_SYMBOL =
			Pattern.compile("[A-Z0-9][A-Z0-9._:/-]{0,31}");

	public Symbol {
		Objects.requireNonNull(value, "symbol must not be null");
		value = value.trim().toUpperCase(Locale.ROOT);
		if (!VALID_SYMBOL.matcher(value).matches()) {
			throw new IllegalArgumentException("invalid symbol: " + value);
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
