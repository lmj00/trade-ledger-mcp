package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.lmj.tradeledger.application.service.AnalyzeTradesService;
import io.github.lmj.tradeledger.application.service.SummarizeJournalService;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import org.junit.jupiter.api.Test;

class TradeLedgerToolsTest {

	@Test
	void analyzesStructuredTradesWithoutReadingJournalFile() {
		AnalyzeTradesService analyzeTradesService =
				new AnalyzeTradesService(new RealizedPnlCalculator());
		SummarizeJournalService summarizeJournalService = new SummarizeJournalService(
				journalName -> {
					throw new AssertionError("journal reader must not be called");
				},
				analyzeTradesService);
		TradeLedgerTools tools = new TradeLedgerTools(
				summarizeJournalService,
				analyzeTradesService);
		TradeInput input = new TradeInput(
				"trade-1",
				"BTC-USDT",
				"CRYPTO",
				"LONG",
				"0.5",
				"60000.25",
				"61000.75",
				"2.125",
				"USDT",
				"2026-08-01T00:00:00Z",
				"2026-08-01T01:00:00Z",
				"breakout");

		TradeAnalysisResponse result = tools.analyzeTrades(List.of(input));

		assertThat(result.tradeCount()).isEqualTo(1);
		assertThat(result.winningTrades()).isEqualTo(1);
		assertThat(result.totalGrossPnl()).isEqualTo("500.25");
		assertThat(result.totalFees()).isEqualTo("2.125");
		assertThat(result.totalNetPnl()).isEqualTo("498.125");
	}
}
