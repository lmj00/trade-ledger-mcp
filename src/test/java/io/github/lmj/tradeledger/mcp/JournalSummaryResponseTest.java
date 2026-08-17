package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.domain.model.Money;
import org.junit.jupiter.api.Test;

class JournalSummaryResponseTest {

	@Test
	void mapsSummaryAmountsToPlainDecimalStrings() {
		JournalSummary summary = new JournalSummary(
				"sample-trades",
				1,
				1,
				0,
				0,
				Money.of("1000.00", "USDT"),
				Money.of("0.1250", "USDT"),
				Money.of("999.8750", "USDT"));

		JournalSummaryResponse result = JournalSummaryResponse.from(summary);

		assertThat(result.journalName()).isEqualTo("sample-trades");
		assertThat(result.tradeCount()).isEqualTo(1);
		assertThat(result.winningTrades()).isEqualTo(1);
		assertThat(result.currency()).isEqualTo("USDT");
		assertThat(result.totalGrossPnl()).isEqualTo("1000");
		assertThat(result.totalFees()).isEqualTo("0.125");
		assertThat(result.totalNetPnl()).isEqualTo("999.875");
	}

	@Test
	void rejectsNullSummary() {
		assertThatNullPointerException()
				.isThrownBy(() -> JournalSummaryResponse.from(null))
				.withMessageContaining("summary");
	}
}
