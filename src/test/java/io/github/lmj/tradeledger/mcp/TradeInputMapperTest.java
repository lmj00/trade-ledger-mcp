package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TradeInputMapperTest {

	private final TradeInputMapper mapper = new TradeInputMapper();

	@ParameterizedTest
	@MethodSource("stringFields")
	void acceptsRawFieldAtLengthLimit(String field, int position, String value, int limit) {
		String raw = switch (field) {
			case "tradeId", "strategy" -> "a".repeat(limit);
			case "quantity", "entryPrice", "exitPrice", "fees" -> "+1." + "2".repeat(limit - 3);
			default -> " ".repeat(limit - value.length()) + value;
		};

		Trade result = mapper.map(List.of(withField(position, raw))).getFirst();

		assertThat(raw).hasSize(limit);
		assertThat(result).isNotNull();
		if (position >= 4 && position <= 7) {
			BigDecimal amount = switch (field) {
				case "quantity" -> result.quantity();
				case "entryPrice" -> result.entryPrice().amount();
				case "exitPrice" -> result.exitPrice().amount();
				case "fees" -> result.fees().amount();
				default -> throw new AssertionError(field);
			};
			assertThat(amount).isEqualByComparingTo(raw);
			assertThat(amount.scale()).isEqualTo(limit - 3);
		}
	}

	@ParameterizedTest
	@MethodSource("stringFields")
	void rejectsRawFieldAboveLengthLimit(String field, int position, String value, int limit) {
		String raw = "9".repeat(limit + 1);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(List.of(withField(position, value), withField(position, raw))))
				.withMessage("trades[1]." + field + " must be at most " + limit + " characters")
				.withMessageNotContaining(raw)
				.withNoCause();
	}

	@ParameterizedTest
	@MethodSource("stringFields")
	void rejectsLongWhitespacePrefixBeforeTrimming(String field, int position, String value, int limit) {
		String raw = " ".repeat(limit + 1 - value.length()) + value;

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(List.of(withField(position, raw))))
				.withMessage("trades[0]." + field + " must be at most " + limit + " characters")
				.withMessageNotContaining(raw)
				.withNoCause();
	}

	private static Stream<Arguments> stringFields() {
		return Stream.of(
				Arguments.of("tradeId", 0, "trade-1", 256),
				Arguments.of("symbol", 1, "BTC-USDT", 256),
				Arguments.of("market", 2, "CRYPTO", 256),
				Arguments.of("direction", 3, "LONG", 256),
				Arguments.of("quantity", 4, "1", 128),
				Arguments.of("entryPrice", 5, "100", 128),
				Arguments.of("exitPrice", 6, "110", 128),
				Arguments.of("fees", 7, "1", 128),
				Arguments.of("currency", 8, "USDT", 256),
				Arguments.of("openedAt", 9, "2026-08-01T09:00:00+09:00", 256),
				Arguments.of("closedAt", 10, "2026-08-01T11:00:00+09:00", 256),
				Arguments.of("strategy", 11, "breakout", 256));
	}

	private static TradeInput withField(int position, String value) {
		String[] fields = { "trade-1", "BTC-USDT", "CRYPTO", "LONG", "1", "100", "110", "1",
				"USDT", "2026-08-01T09:00:00+09:00", "2026-08-01T11:00:00+09:00", "breakout" };
		fields[position] = value;
		return new TradeInput(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
				fields[6], fields[7], fields[8], fields[9], fields[10], fields[11]);
	}

	@Test
	void mapsPlainDecimalStringsEnumsAndOffsetTimestamps() {
		TradeInput input = tradeInput(
				"trade-1",
				"crypto",
				"long",
				"0.125",
				"60000.50",
				"62000.75",
				"5.125");

		Trade result = mapper.map(List.of(input)).getFirst();

		assertThat(result.tradeId()).isEqualTo("trade-1");
		assertThat(result.market()).isEqualTo(Market.CRYPTO);
		assertThat(result.direction()).isEqualTo(Direction.LONG);
		assertThat(result.quantity()).isEqualByComparingTo("0.125");
		assertThat(result.entryPrice().amount()).isEqualByComparingTo("60000.50");
		assertThat(result.fees().amount()).isEqualByComparingTo("5.125");
		assertThat(result.openedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
	}

	@Test
	void rejectsFormattedNumberInsteadOfPlainDecimalString() {
		TradeInput input = tradeInput(
				"trade-1",
				"CRYPTO",
				"LONG",
				"0.125",
				"60,000.50",
				"62000.75",
				"5.125");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(List.of(input)))
				.withMessageContaining("trades[0].entryPrice")
				.withMessageContaining("plain decimal");
	}

	@Test
	void rejectsMoreThanOneHundredTradesBeforeMapping() {
		List<TradeInput> inputs = Collections.nCopies(
				TradeInputMapper.MAX_TRADES + 1,
				tradeInput("trade-1", "CRYPTO", "LONG", "1", "100", "110", "1"));

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(inputs))
				.withMessageContaining("at most 100");
	}

	@Test
	void reportsInvalidTimestampLocation() {
		TradeInput valid = tradeInput(
				"trade-1",
				"CRYPTO",
				"LONG",
				"1",
				"100",
				"110",
				"1");
		TradeInput invalid = new TradeInput(
				valid.tradeId(),
				valid.symbol(),
				valid.market(),
				valid.direction(),
				valid.quantity(),
				valid.entryPrice(),
				valid.exitPrice(),
				valid.fees(),
				valid.currency(),
				"2026-08-01 09:00",
				valid.closedAt(),
				valid.strategy());

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(List.of(invalid)))
				.withMessageContaining("trades[0].openedAt")
				.withMessageContaining("ISO-8601");
	}

	@Test
	void reportsDomainRuleFailureAtItsInputField() {
		TradeInput input = tradeInput(
				"trade-1",
				"CRYPTO",
				"LONG",
				"1",
				"100",
				"110",
				"-1");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> mapper.map(List.of(input)))
				.withMessageContaining("trades[0].fees")
				.withMessageContaining("zero or positive");
	}

	private static TradeInput tradeInput(
			String tradeId,
			String market,
			String direction,
			String quantity,
			String entryPrice,
			String exitPrice,
			String fees) {
		return new TradeInput(
				tradeId,
				"BTC-USDT",
				market,
				direction,
				quantity,
				entryPrice,
				exitPrice,
				fees,
				"USDT",
				"2026-08-01T09:00:00+09:00",
				"2026-08-01T11:00:00+09:00",
				"breakout");
	}
}
