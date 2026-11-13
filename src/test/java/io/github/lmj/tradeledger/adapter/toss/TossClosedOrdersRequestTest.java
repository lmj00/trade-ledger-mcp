package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TossClosedOrdersRequestTest {

	@Test
	void fixesClosedStatusAndDefaultsOnlyLimit() {
		var request = new TossClosedOrdersRequest();
		assertThat(request.status()).isEqualTo("CLOSED");
		assertThat(request.limit()).isEqualTo(20);
		assertThat(request.symbol()).isNull();
		assertThat(request.from()).isNull();
		assertThat(request.to()).isNull();
		assertThat(request.cursor()).isNull();
		for (int limit : List.of(1, 100)) {
			assertThat(new TossClosedOrdersRequest(null, null, null, null, limit).limit()).isEqualTo(limit);
		}
		for (int limit : List.of(0, 101, -1, Integer.MAX_VALUE)) {
			reject(() -> new TossClosedOrdersRequest(null, null, null, null, limit));
		}
	}

	@Test
	void preservesSymbolCaseAndOpaqueCursorWithProjectUtf16Bounds() {
		for (String symbol : List.of("005930", "0101N0", "aApL", "BRK.B", "Brk-b", ".", "-", "x".repeat(256))) {
			assertThat(new TossClosedOrdersRequest(symbol, null, null, null, null).symbol()).isEqualTo(symbol);
		}
		for (String cursor : List.of(" =+/%한😀 ", "%2F", "x".repeat(256), "😀".repeat(128))) {
			assertThat(new TossClosedOrdersRequest(null, null, null, cursor, null).cursor()).isEqualTo(cursor);
		}
		for (String invalid : List.of("", " ", "\u2003", "a\n", "a\u007f", "x".repeat(257),
				"😀".repeat(128) + "x", "\ud800", "\udc00", "a\ud800x", "\udc00\ud800")) {
			reject(() -> new TossClosedOrdersRequest(null, null, null, invalid, null));
			reject(() -> new TossClosedOrdersRequest(invalid, null, null, null, null));
		}
		for (String invalid : List.of(" AAPL", "AAPL ", "A_APL", "한", "A/B", "A+B", "A%B")) {
			reject(() -> new TossClosedOrdersRequest(invalid, null, null, null, null));
		}
	}

	@Test
	void acceptsIndependentInclusiveDatesWithoutInventingBoundariesOrMaximumRange() {
		assertThat(new TossClosedOrdersRequest(null, "2024-02-29", null, null, null).to()).isNull();
		assertThat(new TossClosedOrdersRequest(null, null, "2024-02-29", null, null).from()).isNull();
		var same = new TossClosedOrdersRequest(null, "2024-02-29", "2024-02-29", null, null);
		assertThat(same.from()).isEqualTo(same.to());
		assertThat(new TossClosedOrdersRequest(null, "0001-01-01", "9999-12-31", null, null).from())
				.isEqualTo("0001-01-01");
		reject(() -> new TossClosedOrdersRequest(null, "2024-03-01", "2024-02-29", null, null));
		for (String value : List.of("", " ", "2026-02-29", "2024-02-30", "2026-04-31", "2026-13-01",
				"2026-00-01", "2026-01-00", "2026-1-01", "+2026-01-01", "2026-01-01Z",
				"2026-01-01T00:00:00Z", " 2026-01-01", "2026-01-01 ", "fake-secret-marker")) {
			reject(() -> new TossClosedOrdersRequest(null, value, null, null, null));
			reject(() -> new TossClosedOrdersRequest(null, null, value, null, null));
		}
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss closed orders request");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}
}
