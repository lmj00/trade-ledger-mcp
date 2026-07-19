package io.github.lmj.tradeledger.adapter.csv;

import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.INVALID_FIELD;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.CLOSED_AT;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.CURRENCY;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.DIRECTION;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.ENTRY_PRICE;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.EXIT_PRICE;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.FEES;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.MARKET;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.OPENED_AT;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.QUANTITY;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.STRATEGY;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.SYMBOL;
import static io.github.lmj.tradeledger.adapter.csv.CsvTradeSchema.TRADE_ID;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.Symbol;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.apache.commons.csv.CSVRecord;

final class CsvTradeMapper {

	private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

	Trade map(CSVRecord record, long lineNumber) {
		String tradeId = required(record, TRADE_ID, lineNumber);
		Symbol symbol = valueObject(
				record,
				SYMBOL,
				lineNumber,
				Symbol::new);
		Market market = enumValue(record, MARKET, lineNumber, Market.class);
		Direction direction = enumValue(record, DIRECTION, lineNumber, Direction.class);
		BigDecimal quantity = decimal(record, QUANTITY, lineNumber);
		BigDecimal entryPrice = decimal(record, ENTRY_PRICE, lineNumber);
		BigDecimal exitPrice = decimal(record, EXIT_PRICE, lineNumber);
		BigDecimal fees = decimal(record, FEES, lineNumber);
		CurrencyCode currency = valueObject(
				record,
				CURRENCY,
				lineNumber,
				CurrencyCode::new);
		Instant openedAt = instant(record, OPENED_AT, lineNumber);
		Instant closedAt = instant(record, CLOSED_AT, lineNumber);
		String strategy = required(record, STRATEGY, lineNumber);

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
			throw invalidField(
					lineNumber,
					fieldForDomainFailure(exception.getMessage()),
					exception.getMessage(),
					exception);
		}
	}

	private static String required(CSVRecord record, String field, long lineNumber) {
		String value;
		try {
			value = record.get(field);
		}
		catch (IllegalArgumentException exception) {
			throw invalidField(lineNumber, field, "missing required field", exception);
		}

		if (value == null || value.trim().isEmpty()) {
			throw invalidField(lineNumber, field, "field must not be blank", null);
		}
		return value.trim();
	}

	private static BigDecimal decimal(CSVRecord record, String field, long lineNumber) {
		String value = required(record, field, lineNumber);
		if (!PLAIN_DECIMAL.matcher(value).matches()) {
			throw invalidField(lineNumber, field, "field must be a plain decimal number", null);
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException exception) {
			throw invalidField(lineNumber, field, "field must be a valid decimal number", exception);
		}
	}

	private static Instant instant(CSVRecord record, String field, long lineNumber) {
		String value = required(record, field, lineNumber);
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException exception) {
			throw invalidField(
					lineNumber,
					field,
					"field must be an ISO-8601 timestamp with an offset",
					exception);
		}
	}

	private static <E extends Enum<E>> E enumValue(
			CSVRecord record,
			String field,
			long lineNumber,
			Class<E> enumType) {
		String value = required(record, field, lineNumber).toUpperCase(Locale.ROOT);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw invalidField(
					lineNumber,
					field,
					"unsupported %s value".formatted(field),
					exception);
		}
	}

	private static <T> T valueObject(
			CSVRecord record,
			String field,
			long lineNumber,
			ValueFactory<T> factory) {
		String value = required(record, field, lineNumber);
		try {
			return factory.create(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalidField(lineNumber, field, exception.getMessage(), exception);
		}
	}

	private static String fieldForDomainFailure(String message) {
		if (message == null) {
			return null;
		}
		if (message.startsWith("quantity")) {
			return QUANTITY;
		}
		if (message.startsWith("entry price")) {
			return ENTRY_PRICE;
		}
		if (message.startsWith("exit price")) {
			return EXIT_PRICE;
		}
		if (message.startsWith("fees")) {
			return FEES;
		}
		if (message.startsWith("closedAt")) {
			return CLOSED_AT;
		}
		return null;
	}

	private static CsvJournalException invalidField(
			long lineNumber,
			String field,
			String message,
			Throwable cause) {
		return new CsvJournalException(INVALID_FIELD, lineNumber, field, message, cause);
	}

	@FunctionalInterface
	private interface ValueFactory<T> {

		T create(String value);
	}
}
