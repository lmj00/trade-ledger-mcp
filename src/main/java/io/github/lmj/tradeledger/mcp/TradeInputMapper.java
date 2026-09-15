package io.github.lmj.tradeledger.mcp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.Symbol;
import io.github.lmj.tradeledger.domain.model.Trade;

final class TradeInputMapper {

	static final int MAX_TRADES = 100;

	private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

	List<Trade> map(List<TradeInput> inputs) {
		Objects.requireNonNull(inputs, "trades must not be null");
		if (inputs.isEmpty()) {
			throw new IllegalArgumentException("at least one trade is required");
		}
		if (inputs.size() > MAX_TRADES) {
			throw new IllegalArgumentException(
					"at most " + MAX_TRADES + " trades are allowed per request");
		}

		List<Trade> trades = new ArrayList<>(inputs.size());
		for (int index = 0; index < inputs.size(); index++) {
			TradeInput input = Objects.requireNonNull(
					inputs.get(index),
					"trades[" + index + "] must not be null");
			trades.add(map(input, index));
		}
		return List.copyOf(trades);
	}

	private static Trade map(TradeInput input, int index) {
		String tradeId = required(input.tradeId(), index, "tradeId");
		Symbol symbol = symbol(input.symbol(), index);
		Market market = enumValue(input.market(), index, "market", Market.class);
		Direction direction = enumValue(input.direction(), index, "direction", Direction.class);
		BigDecimal quantity = decimal(input.quantity(), index, "quantity");
		BigDecimal entryPrice = decimal(input.entryPrice(), index, "entryPrice");
		BigDecimal exitPrice = decimal(input.exitPrice(), index, "exitPrice");
		BigDecimal fees = decimal(input.fees(), index, "fees");
		CurrencyCode currency = currency(input.currency(), index);
		Instant openedAt = instant(input.openedAt(), index, "openedAt");
		Instant closedAt = instant(input.closedAt(), index, "closedAt");
		String strategy = required(input.strategy(), index, "strategy");

		try {
			return new Trade(
					tradeId,
					symbol,
					market,
					direction,
					quantity,
					new Money(entryPrice, currency),
					new Money(exitPrice, currency),
					new Money(fees, currency),
					openedAt,
					closedAt,
					strategy);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(
					index,
					fieldForDomainFailure(exception.getMessage()),
					exception.getMessage(),
					exception);
		}
	}

	private static String fieldForDomainFailure(String message) {
		if (message == null) {
			return null;
		}
		if (message.startsWith("quantity")) {
			return "quantity";
		}
		if (message.startsWith("entry price")) {
			return "entryPrice";
		}
		if (message.startsWith("exit price")) {
			return "exitPrice";
		}
		if (message.startsWith("fees")) {
			return "fees";
		}
		if (message.startsWith("closedAt")) {
			return "closedAt";
		}
		return null;
	}

	private static Symbol symbol(String rawValue, int index) {
		String value = required(rawValue, index, "symbol");
		try {
			return new Symbol(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(index, "symbol", exception.getMessage(), exception);
		}
	}

	private static CurrencyCode currency(String rawValue, int index) {
		String value = required(rawValue, index, "currency");
		try {
			return new CurrencyCode(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(index, "currency", exception.getMessage(), exception);
		}
	}

	private static String required(String value, int index, String field) {
		if (value == null || value.trim().isEmpty()) {
			throw invalid(index, field, "must not be blank", null);
		}
		return value.trim();
	}

	private static BigDecimal decimal(String rawValue, int index, String field) {
		String value = required(rawValue, index, field);
		if (!PLAIN_DECIMAL.matcher(value).matches()) {
			throw invalid(index, field, "must be a plain decimal string", null);
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException exception) {
			throw invalid(index, field, "must be a valid decimal string", exception);
		}
	}

	private static Instant instant(String rawValue, int index, String field) {
		String value = required(rawValue, index, field);
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException exception) {
			throw invalid(
					index,
					field,
					"must be an ISO-8601 timestamp with an offset",
					exception);
		}
	}

	private static <E extends Enum<E>> E enumValue(
			String rawValue,
			int index,
			String field,
			Class<E> enumType) {
		String value = required(rawValue, index, field).toUpperCase(Locale.ROOT);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(index, field, "contains an unsupported value", exception);
		}
	}

	private static IllegalArgumentException invalid(
			int index,
			String field,
			String message,
			Throwable cause) {
		String location = "trades[" + index + "]";
		if (field != null) {
			location += "." + field;
		}
		return new IllegalArgumentException(location + " " + message, cause);
	}
}
