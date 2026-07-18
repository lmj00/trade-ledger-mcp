package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CurrencyCodeTest {

	@Test
	void normalizesCryptoCurrencyCode() {
		CurrencyCode currency = new CurrencyCode(" usdt ");

		assertThat(currency.value()).isEqualTo("USDT");
	}

	@Test
	void rejectsInvalidCurrencyCode() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new CurrencyCode("U$D"))
				.withMessageContaining("currency code");
	}
}
