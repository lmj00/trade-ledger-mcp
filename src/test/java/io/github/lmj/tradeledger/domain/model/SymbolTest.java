package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Locale;

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

	@Test
	void rejectsInvalidSymbolWithoutExposingInput() {
		String marker = "synthetic-symbol-a7q9";
		String raw = marker + "!";
		assertThat(raw.length()).isLessThanOrEqualTo(32);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Symbol(raw))
				.withMessage("invalid symbol")
				.withMessageNotContaining(marker)
				.withMessageNotContaining(marker.toUpperCase(Locale.ROOT))
				.withNoCause();
	}

	@Test
	void preservesSymbolLengthLimit() {
		assertThat(new Symbol("a".repeat(32)).value()).isEqualTo("A".repeat(32));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Symbol("a".repeat(33)))
				.withMessageContaining("invalid symbol");
	}
}
