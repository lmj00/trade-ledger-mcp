package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.lmj.tradeledger.domain.model.Money;
import org.junit.jupiter.api.Test;

class JournalSummaryTest {

	@Test
	void createsConsistentSummary() {
		new JournalSummary(
				"sample-trades",
				3,
				2,
				1,
				0,
				Money.of("350", "USDT"),
				Money.of("11", "USDT"),
				Money.of("339", "USDT"));
	}

	@Test
	void rejectsCountsThatDoNotAddUpToTradeCount() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new JournalSummary(
						"sample-trades",
						3,
						1,
						1,
						0,
						Money.of("10", "USDT"),
						Money.of("1", "USDT"),
						Money.of("9", "USDT")))
				.withMessageContaining("add up");
	}

	@Test
	void rejectsTotalsWithDifferentCurrencies() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new JournalSummary(
						"sample-trades",
						1,
						1,
						0,
						0,
						Money.of("10", "USDT"),
						Money.of("1", "USD"),
						Money.of("9", "USDT")))
				.withMessageContaining("same currency");
	}

	@Test
	void rejectsIncorrectNetTotal() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new JournalSummary(
						"sample-trades",
						1,
						1,
						0,
						0,
						Money.of("10", "USDT"),
						Money.of("1", "USDT"),
						Money.of("8", "USDT")))
				.withMessageContaining("gross PnL minus total fees");
	}

	@Test
	void rejectsBlankJournalName() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new JournalSummary(
						" ",
						0,
						0,
						0,
						0,
						Money.of("0", "USDT"),
						Money.of("0", "USDT"),
						Money.of("0", "USDT")))
				.withMessageContaining("journal name");
	}

	@Test
	void rejectsNullTotals() {
		assertThatNullPointerException()
				.isThrownBy(() -> new JournalSummary(
						"sample-trades",
						0,
						0,
						0,
						0,
						null,
						Money.of("0", "USDT"),
						Money.of("0", "USDT")))
				.withMessageContaining("gross");
	}
}
