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
				new RealizedPnlCalculator());

		JournalSummary result = service.summarize("sample-trades");

		assertThat(result.journalName()).isEqualTo("sample-trades");
		assertThat(result.tradeCount()).isEqualTo(3);
		assertThat(result.winningTrades()).isEqualTo(2);
		assertThat(result.losingTrades()).isEqualTo(1);
		assertThat(result.breakEvenTrades()).isZero();
		assertThat(result.totalGrossPnl().amount()).isEqualByComparingTo("367.29645");
		assertThat(result.totalFees().amount()).isEqualByComparingTo("11.51");
		assertThat(result.totalNetPnl().amount()).isEqualByComparingTo("355.78645");
		assertThat(result.totalNetPnl().currency().value()).isEqualTo("USDT");
	}
}
