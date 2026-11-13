package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TossOrderRequestTest {

	@Test
	void acceptsOnlyAnOpaqueOrderIdWithoutNormalizationOrDecoding() {
		assertThat(Arrays.stream(TossOrderRequest.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("orderId");
		for (String id : List.of("0", "9223372036854775808", "AbC_-", " Order%2F+한글😀 ",
				"00000000-0000-0000-0000-000000000001", "x".repeat(256), "😀".repeat(128))) {
			assertThat(new TossOrderRequest(id).orderId()).isEqualTo(id);
		}
		assertThat(TossOrderRequest.MAX_ORDER_ID_LENGTH).isEqualTo(256);
	}

	@Test
	void rejectsNullBlankAndOver256Utf16UnitsSafely() {
		for (String id : new String[] {null, "", "  ", "\u2003\u3000", "x".repeat(257),
				"😀".repeat(128) + "x", "x".repeat(255) + "😀"}) {
			reject(id);
		}
	}

	@Test
	void rejectsEveryIsoControlWithoutExposingTheOriginalId() {
		for (int c = 0; c <= 0x9f; c++) {
			if (Character.isISOControl(c)) {
				reject("fake-secret-marker" + (char) c);
			}
		}
	}

	private static void reject(String id) {
		assertThatThrownBy(() -> new TossOrderRequest(id)).isInstanceOfSatisfying(
				IllegalArgumentException.class, exception -> {
					assertThat(exception.getMessage()).isEqualTo("Invalid Toss order request");
					assertThat(exception.getCause()).isNull();
					assertThat(exception.getSuppressed()).isEmpty();
					var stack = new StringWriter();
					exception.printStackTrace(new PrintWriter(stack));
					assertThat(stack.toString()).doesNotContain("fake-secret-marker");
				});
	}
}
