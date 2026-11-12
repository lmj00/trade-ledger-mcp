package io.github.lmj.tradeledger.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import io.github.lmj.tradeledger.application.model.TradeAnalysis;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.Symbol;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalyzeTradesServiceTest {

	private final AnalyzeTradesService service =
			new AnalyzeTradesService(new RealizedPnlCalculator());

	@Test
	void analyzesWinningLosingAndBreakEvenTrades() {
		List<Trade> trades = List.of(
				trade("trade-1", Direction.LONG, "100", "120", "2", "3", "USD"),
				trade("trade-2", Direction.LONG, "120", "100", "2", "3", "USD"),
				trade("trade-3", Direction.SHORT, "120", "118", "2", "4", "USD"));

		TradeAnalysis result = service.analyze(trades);

		assertThat(result.tradeCount()).isEqualTo(3);
		assertThat(result.winningTrades()).isEqualTo(1);
		assertThat(result.losingTrades()).isEqualTo(1);
		assertThat(result.breakEvenTrades()).isEqualTo(1);
		assertThat(result.totalGrossPnl().amount()).isEqualByComparingTo("4");
		assertThat(result.totalFees().amount()).isEqualByComparingTo("10");
		assertThat(result.totalNetPnl().amount()).isEqualByComparingTo("-6");
	}

	@Test
	void rejectsEmptyTradeList() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.analyze(List.of()))
				.withMessageContaining("at least one trade");
	}

	@Test
	void rejectsNullTradeElement() {
		assertThatNullPointerException()
				.isThrownBy(() -> service.analyze(java.util.Arrays.asList((Trade) null)))
				.withMessageContaining("index 0");
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void rejectsDuplicateTradeIdsWithoutExposingInput(int duplicateIndex) {
		String marker = "synthetic-id-b8r2";
		Trade first = trade(marker, Direction.LONG, "100", "120", "1", "1", "USD");
		Trade other = trade("trade-2", Direction.LONG, "100", "120", "1", "1", "USD");
		Trade repeated = trade(marker, Direction.SHORT, "120", "100", "2", "1", "USD");
		List<Trade> trades = duplicateIndex == 1
				? List.of(first, repeated, other) : List.of(first, other, repeated);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.analyze(trades))
				.withMessageContaining("unique")
				.withMessageContaining("index " + duplicateIndex)
				.withMessageNotContaining(marker)
				.withMessageNotContaining(marker.toUpperCase(Locale.ROOT))
				.withNoCause();
	}

	@Test
	void rejectsMixedCurrencies() {
		List<Trade> trades = List.of(
				trade("trade-1", Direction.LONG, "100", "120", "1", "1", "USD"),
				trade("trade-2", Direction.LONG, "100", "120", "1", "1", "KRW"));

		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.analyze(trades))
				.withMessageContaining("same currency");
	}

	private static Trade trade(
			String tradeId,
			Direction direction,
			String entryPrice,
			String exitPrice,
			String quantity,
			String fees,
			String currency) {
		return new Trade(
				tradeId,
				new Symbol("BTC-" + currency),
				Market.CRYPTO,
				direction,
				new BigDecimal(quantity),
				Money.of(entryPrice, currency),
				Money.of(exitPrice, currency),
				Money.of(fees, currency),
				Instant.parse("2026-07-20T01:00:00Z"),
				Instant.parse("2026-07-20T02:00:00Z"),
				"test-strategy");
	}
}
