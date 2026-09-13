package io.github.lmj.tradeledger.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.Symbol;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import org.junit.jupiter.api.Test;

class SummarizeJournalServiceTest {

	@Test
	void summarizesWinningLosingAndBreakEvenTrades() {
		List<Trade> trades = List.of(
				trade("trade-1", Direction.LONG, "100", "120", "2", "3", "USD"),
				trade("trade-2", Direction.LONG, "120", "100", "2", "3", "USD"),
				trade("trade-3", Direction.SHORT, "120", "118", "2", "4", "USD"));
		TradeJournalReader reader = journalName -> {
			assertThat(journalName).isEqualTo("test-journal");
			return trades;
		};
		SummarizeJournalService service =
				new SummarizeJournalService(
						reader,
						new AnalyzeTradesService(new RealizedPnlCalculator()));

		JournalSummary result = service.summarize("test-journal");

		assertThat(result.journalName()).isEqualTo("test-journal");
		assertThat(result.analysis().tradeCount()).isEqualTo(3);
		assertThat(result.analysis().winningTrades()).isEqualTo(1);
		assertThat(result.analysis().losingTrades()).isEqualTo(1);
		assertThat(result.analysis().breakEvenTrades()).isEqualTo(1);
		assertThat(result.analysis().totalGrossPnl().amount()).isEqualByComparingTo("4");
		assertThat(result.analysis().totalFees().amount()).isEqualByComparingTo("10");
		assertThat(result.analysis().totalNetPnl().amount()).isEqualByComparingTo("-6");
	}

	@Test
	void rejectsEmptyJournalReturnedByReader() {
		SummarizeJournalService service = new SummarizeJournalService(
				journalName -> List.of(),
				new AnalyzeTradesService(new RealizedPnlCalculator()));

		assertThatIllegalStateException()
				.isThrownBy(() -> service.summarize("empty"))
				.withMessageContaining("at least one trade");
	}

	@Test
	void rejectsNullReturnedByReader() {
		SummarizeJournalService service = new SummarizeJournalService(
				journalName -> null,
				new AnalyzeTradesService(new RealizedPnlCalculator()));

		assertThatNullPointerException()
				.isThrownBy(() -> service.summarize("broken"))
				.withMessageContaining("must not return null");
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
