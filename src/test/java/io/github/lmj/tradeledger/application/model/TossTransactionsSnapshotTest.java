package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class TossTransactionsSnapshotTest {

	@Test
	void defensivelyCopiesSourceOrderAndRetainsEqualRows() {
		var first = row("BUY");
		var second = row("SELL");
		var rows = new ArrayList<>(List.of(first, second, row("BUY")));
		var page = new TossTransactionsSnapshot(rows, " =+/%한😀 ", true);
		rows.clear();
		assertThat(page.transactions()).containsExactly(first, second, first);
		assertThat(page.nextCursor()).isEqualTo(" =+/%한😀 ");
		assertThat(page.hasNext()).isTrue();
		assertThatThrownBy(() -> page.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void acceptsEmptyFinalEmptyContinuingShortAndHundredRowPages() {
		assertThat(new TossTransactionsSnapshot(List.of(), null, false).transactions()).isEmpty();
		assertThat(new TossTransactionsSnapshot(List.of(), "cursor", true).hasNext()).isTrue();
		for (String cursor : List.of("x".repeat(256), "😀".repeat(128), "=+/%한😀")) {
			assertThat(new TossTransactionsSnapshot(List.of(row("BUY")), cursor, true).nextCursor())
					.isEqualTo(cursor);
		}
		assertThat(new TossTransactionsSnapshot(Collections.nCopies(100, row("BUY")), null, false)
				.transactions()).hasSize(100);
	}

	@Test
	void rejectsNullsOversizeAndInconsistentOrInvalidCursorsSafely() {
		reject(() -> new TossTransactionsSnapshot(null, null, false));
		reject(() -> new TossTransactionsSnapshot(Arrays.asList(row("BUY"), null), null, false));
		reject(() -> new TossTransactionsSnapshot(Collections.nCopies(101, row("BUY")), null, false));
		reject(() -> new TossTransactionsSnapshot(List.of(), null, true));
		for (String cursor : List.of("", " ", "\u2003", "a\n", "a\u007f", "a\u0085", "x".repeat(257),
				"😀".repeat(128) + "x", "\ud800", "\udc00", "a\ud800x", "\udc00\ud800")) {
			reject(() -> new TossTransactionsSnapshot(List.of(), cursor, true));
		}
		for (String cursor : List.of("", "cursor", "fake-private-marker")) {
			reject(() -> new TossTransactionsSnapshot(List.of(), cursor, false));
		}
	}

	private static TossTransactionSnapshot row(String type) {
		return new TossTransactionSnapshot(type, "KR", "KRW", null, null, null, null, null, "1.00",
				"0", "0", "1.00", null, LocalDate.of(2026, 3, 1), true, null, false);
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss transactions summary");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}
}
