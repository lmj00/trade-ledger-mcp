package io.github.lmj.tradeledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void treatsDifferentScalesAsTheSameAmount() {
		Money oneDecimalPlace = Money.of("10.0", "USD");
		Money threeDecimalPlaces = Money.of("10.000", "usd");

		assertThat(oneDecimalPlace).isEqualTo(threeDecimalPlaces);
	}

	@Test
	void performsArithmeticWithoutUsingDouble() {
		Money result = Money.of("10.25", "USD")
				.multiply(new BigDecimal("2"))
				.subtract(Money.of("0.50", "USD"));

		assertThat(result.amount()).isEqualByComparingTo("20.00");
	}

	@Test
	void rejectsArithmeticAcrossCurrencies() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Money.of("10", "USD").add(Money.of("10", "KRW")))
				.withMessageContaining("currency mismatch");
	}
}
