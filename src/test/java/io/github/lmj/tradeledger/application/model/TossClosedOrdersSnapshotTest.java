package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.application.model.TossOrderSnapshot.ExecutionSummary;
import org.junit.jupiter.api.Test;

class TossClosedOrdersSnapshotTest {

	@Test
	void copiesSourceOrderAndKeepsCaseDistinctIdsWithoutNormalizingMetadata() {
		var orders = new ArrayList<>(List.of(order("id"), order("ID")));
		var page = new TossClosedOrdersSnapshot(orders, " =+/%한😀 ", true);
		orders.clear();
		assertThat(page.orders()).extracting(TossOrderSnapshot::orderId).containsExactly("id", "ID");
		assertThat(page.nextCursor()).isEqualTo(" =+/%한😀 ");
		assertThat(page.hasNext()).isTrue();
		assertThatThrownBy(() -> page.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void acceptsEmptyFinalOrShortContinuingPagesAndExactlyOneHundredOrders() {
		assertThat(new TossClosedOrdersSnapshot(List.of(), null, false).orders()).isEmpty();
		assertThat(new TossClosedOrdersSnapshot(List.of(), "cursor", true).hasNext()).isTrue();
		for (String cursor : List.of("x".repeat(256), "😀".repeat(128), "=+/%한😀")) {
			assertThat(new TossClosedOrdersSnapshot(List.of(order("one")), cursor, true).nextCursor())
					.isEqualTo(cursor);
		}
		var hundred = IntStream.range(0, 100).mapToObj(i -> order("id-" + i)).toList();
		assertThat(new TossClosedOrdersSnapshot(hundred, null, false).orders()).hasSize(100);
	}

	@Test
	void rejectsNullDuplicateOversizedListsAndInconsistentOrInvalidCursorsSafely() {
		reject(() -> new TossClosedOrdersSnapshot(null, null, false));
		reject(() -> new TossClosedOrdersSnapshot(Arrays.asList(order("id"), null), null, false));
		reject(() -> new TossClosedOrdersSnapshot(List.of(order("id"), order("id")), null, false));
		reject(() -> new TossClosedOrdersSnapshot(
				IntStream.range(0, 101).mapToObj(i -> order("id-" + i)).toList(), null, false));
		reject(() -> new TossClosedOrdersSnapshot(List.of(), null, true));
		for (String value : List.of("", " ", "\u2003", "a\n", "a\u007f", "x".repeat(257),
				"😀".repeat(128) + "x", "\ud800", "\udc00", "a\ud800x", "\udc00\ud800")) {
			reject(() -> new TossClosedOrdersSnapshot(List.of(), value, true));
		}
		for (String value : List.of("", "cursor", "fake-secret-marker")) {
			reject(() -> new TossClosedOrdersSnapshot(List.of(), value, false));
		}
	}

	private static TossOrderSnapshot order(String id) {
		return new TossOrderSnapshot(id, "AAPL", "BUY", "LIMIT", "DAY", "PARTIAL_FILLED", null,
				"1.00", null, "USD", Instant.EPOCH, null,
				new ExecutionSummary("0.50", null, null, "-0.0100", "+0.0200", null, null));
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss closed orders summary");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}
}
