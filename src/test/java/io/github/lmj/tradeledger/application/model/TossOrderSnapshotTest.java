package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import io.github.lmj.tradeledger.application.model.TossOrderSnapshot.ExecutionSummary;
import org.junit.jupiter.api.Test;

class TossOrderSnapshotTest {

	@Test
	void isAnImmutableSummaryWithSeparateOrderAndCumulativeExecutionAmounts() {
		var execution = new ExecutionSummary("0.123456789012345678900", "12.3400", "999.0000",
				"-0.0100", "+0.0200", Instant.EPOCH, LocalDate.of(2026, 3, 30));
		var order = order(execution, "13.0000", "5.00");
		assertThat(order.price()).isEqualTo("13.0000");
		assertThat(order.quantity()).isEqualTo("5.00");
		assertThat(order.orderAmount()).isEqualTo("100.00");
		assertThat(order.execution()).isSameAs(execution);
		assertThat(execution.filledQuantity()).isEqualTo("0.123456789012345678900");
		assertThat(execution.averageFilledPrice()).isEqualTo("12.3400");
		assertThat(execution.filledAmount()).isEqualTo("999.0000");
		assertThat(execution.commission()).isEqualTo("-0.0100");
		assertThat(execution.tax()).isEqualTo("+0.0200");
		assertThat(execution.filledAt()).isEqualTo(Instant.EPOCH);
		assertThat(execution.settlementDate()).isEqualTo(LocalDate.of(2026, 3, 30));
		for (Class<?> type : List.of(TossOrderSnapshot.class, ExecutionSummary.class)) {
			assertThat(type.isRecord()).isTrue();
			assertThat(Arrays.stream(type.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())))
					.allMatch(f -> Modifier.isFinal(f.getModifiers()));
		}
		assertThat(Arrays.stream(TossOrderSnapshot.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("orderId", "symbol", "side", "orderType", "timeInForce", "status", "price",
						"quantity", "orderAmount", "currency", "orderedAt", "canceledAt", "execution");
		assertThat(Arrays.stream(ExecutionSummary.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("filledQuantity", "averageFilledPrice", "filledAmount", "commission", "tax",
						"filledAt", "settlementDate");
	}

	@Test
	void keepsNullsAndFutureCodesWithoutInventingFillsOrCosts() {
		var execution = new ExecutionSummary("0", null, null, null, null, null, null);
		var snapshot = order(execution, null, "1");
		assertThat(snapshot.orderType()).isEqualTo("FUTURE_TYPE");
		assertThat(snapshot.timeInForce()).isEqualTo("FUTURE_TIF");
		assertThat(snapshot.status()).isEqualTo("FUTURE_STATUS");
		assertThat(snapshot.price()).isNull();
		assertThat(snapshot.canceledAt()).isNull();
		assertThat(execution.averageFilledPrice()).isNull();
		assertThat(execution.filledAmount()).isNull();
		assertThat(execution.commission()).isNull();
		assertThat(execution.tax()).isNull();
		assertThat(execution.filledAt()).isNull();
		assertThat(execution.settlementDate()).isNull();
	}

	@Test
	void directConstructionRetainsDecimalBoundariesAndRejectsInvalidRequiredDataSafely() {
		var execution = new ExecutionSummary("1".repeat(30), "-1.00", "0", null, null, null, null);
		assertThat(order(execution, "1".repeat(30), "1").price()).hasSize(30);
		for (String invalid : new String[] {null, "", "1e2", "NaN", "Infinity", "1".repeat(31),
				"fake-secret-marker", " 1", ".5"}) {
			assertThatThrownBy(() -> order(execution, "1", invalid))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss order summary")
					.hasNoCause();
			assertThatThrownBy(() -> new ExecutionSummary(invalid, null, null, null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss order summary")
					.hasNoCause();
		}
		assertThatThrownBy(() -> order(null, null, "1"))
				.isInstanceOf(IllegalArgumentException.class).hasNoCause();
	}

	private static TossOrderSnapshot order(ExecutionSummary execution, String price, String quantity) {
		return new TossOrderSnapshot("Order%2F", "005930", "BUY", "FUTURE_TYPE", "FUTURE_TIF",
				"FUTURE_STATUS", price, quantity, "100.00", "KRW", Instant.EPOCH, null, execution);
	}
}
