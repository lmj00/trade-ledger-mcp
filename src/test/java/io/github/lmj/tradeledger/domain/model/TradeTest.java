package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TradeTest {

	private static final Instant OPENED_AT = Instant.parse("2026-07-18T01:00:00Z");
	private static final Instant CLOSED_AT = Instant.parse("2026-07-18T02:00:00Z");

	@Test
	void createsValidClosedTrade() {
		Trade trade = trade(new BigDecimal("0.10"), Money.of("100", "USDT"),
				Money.of("110", "USDT"), Money.of("0.1", "USDT"), CLOSED_AT);

		assertThat(trade.tradeId()).isEqualTo("trade-1");
		assertThat(trade.quantity()).isEqualByComparingTo("0.1");
		assertThat(trade.symbol().value()).isEqualTo("BTC-USDT");
	}

	@Test
	void rejectsZeroQuantity() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> trade(BigDecimal.ZERO, Money.of("100", "USDT"),
						Money.of("110", "USDT"), Money.of("0", "USDT"), CLOSED_AT))
				.withMessageContaining("quantity");
	}

	@Test
	void rejectsNonPositivePrice() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> trade(BigDecimal.ONE, Money.of("0", "USDT"),
						Money.of("110", "USDT"), Money.of("0", "USDT"), CLOSED_AT))
				.withMessageContaining("entry price");
	}

	@Test
	void rejectsNegativeFees() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> trade(BigDecimal.ONE, Money.of("100", "USDT"),
						Money.of("110", "USDT"), Money.of("-0.1", "USDT"), CLOSED_AT))
				.withMessageContaining("fees");
	}

	@Test
	void rejectsMixedCurrencies() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> trade(BigDecimal.ONE, Money.of("100", "USD"),
						Money.of("110", "KRW"), Money.of("0", "USD"), CLOSED_AT))
				.withMessageContaining("same currency");
	}

	@Test
	void rejectsTradeThatDoesNotCloseAfterItOpens() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> trade(BigDecimal.ONE, Money.of("100", "USDT"),
						Money.of("110", "USDT"), Money.of("0", "USDT"), OPENED_AT))
				.withMessageContaining("closedAt");
	}

	@Test
	void rejectsBlankTradeId() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Trade(
						" ",
						new Symbol("BTC-USDT"),
						Market.CRYPTO,
						Direction.LONG,
						BigDecimal.ONE,
						Money.of("100", "USDT"),
						Money.of("110", "USDT"),
						Money.of("0", "USDT"),
						OPENED_AT,
						CLOSED_AT,
						"breakout"))
				.withMessageContaining("tradeId");
	}

	@Test
	void rejectsBlankStrategy() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Trade(
						"trade-1",
						new Symbol("BTC-USDT"),
						Market.CRYPTO,
						Direction.LONG,
						BigDecimal.ONE,
						Money.of("100", "USDT"),
						Money.of("110", "USDT"),
						Money.of("0", "USDT"),
						OPENED_AT,
						CLOSED_AT,
						" "))
				.withMessageContaining("strategy");
	}

	private static Trade trade(
			BigDecimal quantity,
			Money entryPrice,
			Money exitPrice,
			Money fees,
			Instant closedAt) {
		return new Trade(
				"trade-1",
				new Symbol("btc-usdt"),
				Market.CRYPTO,
				Direction.LONG,
				quantity,
				entryPrice,
				exitPrice,
				fees,
				OPENED_AT,
				closedAt,
				"breakout");
	}
}
