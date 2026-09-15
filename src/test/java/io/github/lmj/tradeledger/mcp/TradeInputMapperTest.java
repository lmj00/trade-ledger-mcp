package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.junit.jupiter.api.Test;

class TradeInputMapperTest {

	private final TradeInputMapper mapper = new TradeInputMapper();

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
