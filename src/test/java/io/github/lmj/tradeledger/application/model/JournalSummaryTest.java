package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.lmj.tradeledger.domain.model.Money;
import org.junit.jupiter.api.Test;

class JournalSummaryTest {

	@Test
	void trimsJournalNameAndKeepsAnalysis() {
		TradeAnalysis analysis = analysis();

		JournalSummary summary = new JournalSummary(" sample-trades ", analysis);

		assertThat(summary.journalName()).isEqualTo("sample-trades");
		assertThat(summary.analysis()).isSameAs(analysis);
	}

	@Test
	void rejectsBlankJournalName() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new JournalSummary(" ", analysis()))
				.withMessageContaining("journal name");
	}

	@Test
	void rejectsNullAnalysis() {
		assertThatNullPointerException()
				.isThrownBy(() -> new JournalSummary("sample-trades", null))
				.withMessageContaining("analysis");
	}

	private static TradeAnalysis analysis() {
		return new TradeAnalysis(
				1,
				1,
				0,
				0,
				Money.of("10", "USDT"),
				Money.of("1", "USDT"),
				Money.of("9", "USDT"));
	}
}
