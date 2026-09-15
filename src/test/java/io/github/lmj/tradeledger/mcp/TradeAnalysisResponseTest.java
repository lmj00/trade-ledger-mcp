package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.lmj.tradeledger.application.model.TradeAnalysis;
import io.github.lmj.tradeledger.domain.model.Money;
import org.junit.jupiter.api.Test;

class TradeAnalysisResponseTest {

	@Test
	void mapsAnalysisAmountsToPlainDecimalStrings() {
		TradeAnalysis analysis = new TradeAnalysis(
				1,
				1,
				0,
				0,
				Money.of("1000.00", "USDT"),
				Money.of("0.1250", "USDT"),
				Money.of("999.8750", "USDT"));

		TradeAnalysisResponse result = TradeAnalysisResponse.from(analysis);

		assertThat(result.tradeCount()).isEqualTo(1);
		assertThat(result.winningTrades()).isEqualTo(1);
		assertThat(result.currency()).isEqualTo("USDT");
		assertThat(result.totalGrossPnl()).isEqualTo("1000");
		assertThat(result.totalFees()).isEqualTo("0.125");
		assertThat(result.totalNetPnl()).isEqualTo("999.875");
	}

	@Test
	void rejectsNullAnalysis() {
		assertThatNullPointerException()
				.isThrownBy(() -> TradeAnalysisResponse.from(null))
				.withMessageContaining("analysis");
	}
}
