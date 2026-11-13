package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.application.model.TossClosedOrdersSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TossClosedOrdersResponseTest {
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 4, 100})
	void preservesEveryOrderAndExplicitMetadataWithoutInferringFromSize(int count) {
		var orders = IntStream.range(0, count)
				.mapToObj(i -> TossOrderResponseTest.snapshot(i % 4, "order-" + i)).toList();
		for (boolean hasNext : List.of(false, true)) {
			String cursor = hasNext ? " opaque%2F=+/&?#한😀 " : null;
			var response = TossClosedOrdersResponse.from(new TossClosedOrdersSnapshot(orders, cursor, hasNext));
			assertThat(response.serviceId()).isEqualTo("toss");
			assertThat(response.nextCursor()).isEqualTo(cursor);
			assertThat(response.hasNext()).isEqualTo(hasNext);
			assertThat(response.orders()).containsExactlyElementsOf(orders.stream().map(TossOrderResponse::from).toList());
			assertThatThrownBy(() -> response.orders().add(null)).isInstanceOf(UnsupportedOperationException.class);
			var actual = TossOrderResponseTest.JSON.valueToTree(response);
			assertThat(actual.size()).isEqualTo(4);
			assertThat(actual.has("nextCursor")).isTrue();
			if (!hasNext) { assertThat(actual.get("nextCursor").isNull()).isTrue(); }
			for (int i = 0; i < count; i++) {
				assertThat(actual.get("orders").get(i)).isEqualTo(TossOrderResponseTest.JSON.readTree(
						TossOrderResponseTest.expected(i % 4, "order-" + i)));
			}
		}
	}

	@Test
	void copiesCallerOwnedResponseListAndRejectsNullSource() {
		var orders = new ArrayList<>(List.of(TossOrderResponse.from(TossOrderResponseTest.snapshot(0, "order"))));
		var response = new TossClosedOrdersResponse("toss", orders, null, false);
		orders.clear();
		assertThat(response.orders()).hasSize(1);
		assertThatNullPointerException().isThrownBy(() -> TossClosedOrdersResponse.from(null));
	}
}
