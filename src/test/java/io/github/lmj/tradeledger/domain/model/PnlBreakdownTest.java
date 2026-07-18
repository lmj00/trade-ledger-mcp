package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class PnlBreakdownTest {

	@Test
	void createsValidPnlBreakdown() {
		PnlBreakdown pnl = new PnlBreakdown(
				Money.of("40", "USD"),
				Money.of("3", "USD"),
				Money.of("37", "USD"));

		assertThat(pnl.netPnl()).isEqualTo(Money.of("37", "USD"));
	}

	@Test
	void rejectsMissingGrossPnl() {
		assertThatNullPointerException()
				.isThrownBy(() -> new PnlBreakdown(
						null,
						Money.of("3", "USD"),
						Money.of("37", "USD")))
				.withMessageContaining("gross PnL");
	}

	@Test
	void rejectsNegativeFees() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PnlBreakdown(
						Money.of("40", "USD"),
						Money.of("-3", "USD"),
						Money.of("43", "USD")))
				.withMessageContaining("fees");
	}

	@Test
	void rejectsMixedCurrencies() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PnlBreakdown(
						Money.of("40", "USD"),
						Money.of("3", "KRW"),
						Money.of("37", "USD")))
				.withMessageContaining("same currency");
	}

	@Test
	void rejectsInconsistentNetPnl() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PnlBreakdown(
						Money.of("40", "USD"),
						Money.of("3", "USD"),
						Money.of("100", "USD")))
				.withMessageContaining("gross PnL minus fees");
	}
}
