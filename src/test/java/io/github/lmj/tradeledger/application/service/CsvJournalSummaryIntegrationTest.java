package io.github.lmj.tradeledger.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import org.junit.jupiter.api.Test;

class CsvJournalSummaryIntegrationTest {

	@Test
	void summarizesSanitizedCsvJournalWithoutLosingDecimalPrecision()
			throws URISyntaxException {
		Path journalRoot = Path.of(Objects.requireNonNull(
				getClass().getResource("/journals"),
				"journal test resources must exist").toURI());
		SummarizeJournalService service = new SummarizeJournalService(
				new CsvTradeJournalReader(journalRoot),
				new AnalyzeTradesService(new RealizedPnlCalculator()));

		JournalSummary result = service.summarize("sample-trades");

		assertThat(result.journalName()).isEqualTo("sample-trades");
		assertThat(result.analysis().tradeCount()).isEqualTo(3);
		assertThat(result.analysis().winningTrades()).isEqualTo(2);
		assertThat(result.analysis().losingTrades()).isEqualTo(1);
		assertThat(result.analysis().breakEvenTrades()).isZero();
		assertThat(result.analysis().totalGrossPnl().amount())
				.isEqualByComparingTo("367.29645");
		assertThat(result.analysis().totalFees().amount()).isEqualByComparingTo("11.51");
		assertThat(result.analysis().totalNetPnl().amount())
				.isEqualByComparingTo("355.78645");
		assertThat(result.analysis().totalNetPnl().currency().value()).isEqualTo("USDT");
	}
}
