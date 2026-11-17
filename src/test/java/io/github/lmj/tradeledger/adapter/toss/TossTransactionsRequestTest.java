package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TossTransactionsRequestTest {

	@Test
	void defaultsOnlyLimitAndExposesOnlyFourAllTypesParameters() {
		var request = new TossTransactionsRequest();
		assertThat(request).isEqualTo(new TossTransactionsRequest(null, null, null, null));
		assertThat(request.limit()).isEqualTo(20);
		assertThat(request.from()).isNull();
		assertThat(request.to()).isNull();
		assertThat(request.cursor()).isNull();
		assertThat(Arrays.stream(TossTransactionsRequest.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("from", "to", "cursor", "limit");
		for (int limit : List.of(1, 100)) {
			assertThat(new TossTransactionsRequest(null, null, null, limit).limit()).isEqualTo(limit);
		}
		for (int limit : List.of(0, 101, -1, Integer.MAX_VALUE)) {
			reject(() -> new TossTransactionsRequest(null, null, null, limit));
		}
	}

	@Test
	void acceptsIndependentStrictInclusiveDatesIncludingLeapEqualAndFutureDates() {
		assertThat(new TossTransactionsRequest("2024-02-29", null, null, null).to()).isNull();
		assertThat(new TossTransactionsRequest(null, "2024-02-29", null, null).from()).isNull();
		for (String date : List.of("2024-02-29", "2099-12-31", "9999-12-31")) {
			var request = new TossTransactionsRequest(date, date, null, null);
			assertThat(request.from()).isEqualTo(date);
			assertThat(request.to()).isEqualTo(date);
		}
		assertThat(new TossTransactionsRequest("2000-01-01", "2099-12-31", null, null).to())
				.isEqualTo("2099-12-31");
		reject(() -> new TossTransactionsRequest("2026-03-02", "2026-03-01", null, null));
		for (String date : List.of("", " ", "2026-02-29", "2024-02-30", "2026-04-31", "2026-13-01",
				"2026-01-00", "2026-3-01", "2026-03-1", "+2026-03-01", "10000-01-01",
				"2026-03-01T00:00:00Z", " 2026-03-01", "2026-03-01 ", "fake-private-marker")) {
			reject(() -> new TossTransactionsRequest(date, null, null, null));
			reject(() -> new TossTransactionsRequest(null, date, null, null));
		}
	}

	@Test
	void preservesOpaqueCursorAndChecksProjectUtf16Policy() {
		for (String cursor : List.of(" =+/%한😀 ", "%2F", "x", "x".repeat(256), "😀".repeat(128))) {
			assertThat(new TossTransactionsRequest(null, null, cursor, null).cursor()).isEqualTo(cursor);
		}
		for (String cursor : List.of("", " ", "\u2003", "a\n", "a\u0000", "a\u007f", "a\u0085",
				"x".repeat(257), "😀".repeat(128) + "x", "\ud800", "\udc00", "a\ud800x", "\udc00\ud800")) {
			reject(() -> new TossTransactionsRequest(null, null, cursor, null));
		}
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss transactions request");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}
}
