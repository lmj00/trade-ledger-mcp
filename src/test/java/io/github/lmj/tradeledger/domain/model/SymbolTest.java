package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SymbolTest {

	@Test
	void normalizesSymbol() {
		Symbol symbol = new Symbol(" btc-usdt ");

		assertThat(symbol.value()).isEqualTo("BTC-USDT");
	}

	@Test
	void rejectsSymbolContainingWhitespace() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Symbol("BTC USDT"))
				.withMessageContaining("invalid symbol");
	}
}
