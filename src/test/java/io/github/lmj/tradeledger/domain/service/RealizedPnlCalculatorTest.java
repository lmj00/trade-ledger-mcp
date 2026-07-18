package io.github.lmj.tradeledger.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.PnlBreakdown;
import io.github.lmj.tradeledger.domain.model.Symbol;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RealizedPnlCalculatorTest {

	private final RealizedPnlCalculator calculator = new RealizedPnlCalculator();

	@ParameterizedTest
	@CsvSource({
			"LONG,  100, 120,  40,  37",
			"LONG,  120, 100, -40, -43",
			"SHORT, 120, 100,  40,  37",
			"SHORT, 100, 120, -40, -43"
	})
	void calculatesRealizedPnl(
			Direction direction,
			String entryPrice,
			String exitPrice,
			String expectedGross,
			String expectedNet) {
		Trade trade = trade(direction, entryPrice, exitPrice);

		PnlBreakdown result = calculator.calculate(trade);

		assertThat(result.grossPnl().amount()).isEqualByComparingTo(expectedGross);
		assertThat(result.fees().amount()).isEqualByComparingTo("3");
		assertThat(result.netPnl().amount()).isEqualByComparingTo(expectedNet);
		assertThat(result.netPnl().currency()).isEqualTo(new CurrencyCode("USD"));
	}

	private static Trade trade(Direction direction, String entryPrice, String exitPrice) {
		return new Trade(
				"trade-1",
				new Symbol("AAPL"),
				Market.STOCK,
				direction,
				new BigDecimal("2"),
				Money.of(entryPrice, "USD"),
				Money.of(exitPrice, "USD"),
				Money.of("3", "USD"),
				Instant.parse("2026-07-18T01:00:00Z"),
				Instant.parse("2026-07-18T02:00:00Z"),
				"breakout");
	}
}
