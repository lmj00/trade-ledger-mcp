package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class UpbitClosedOrdersRequestTest {
	@Test
	void defaultsLeaveMarketAndTimeAbsentAndIncludeBothClosedStates() {
		var request = new UpbitClosedOrdersRequest();
		assertThat(request.market()).isNull();
		assertThat(request.state()).isNull();
		assertThat(request.states()).containsExactly("done", "cancel");
		assertThat(request.limit()).isEqualTo(100);
		assertThat(request.orderBy()).isEqualTo("desc");
		assertThat(request.startTime()).isNull();
		assertThat(request.endTime()).isNull();
		assertThatThrownBy(() -> request.states().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void acceptsExplicitStatesLimitsAndSortAndDefensivelyCopiesStates() {
		for (int limit : List.of(1, 1000)) {
			for (String state : List.of("done", "cancel")) {
				var request = request("KRW-BTC", state, null, null, null, limit, "asc");
				assertThat(request.market()).isEqualTo("KRW-BTC");
				assertThat(request.state()).isEqualTo(state);
				assertThat(request.states()).isNull();
				assertThat(request.limit()).isEqualTo(limit);
				assertThat(request.orderBy()).isEqualTo("asc");
			}
		}
		var input = new ArrayList<>(List.of("cancel"));
		var request = request(null, null, input, null, null, null, null);
		input.clear();
		assertThat(request.states()).containsExactly("cancel");
		assertThatThrownBy(() -> request.states().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsInvalidOptionsAndMutuallyExclusiveStates() {
		reject(() -> request(null, "done", List.of("cancel"), null, null, null, null));
		reject(() -> request(null, "done", List.of(), null, null, null, null));
		for (String state : List.of("wait", "watch", "DONE", "", "secret-marker")) {
			reject(() -> request(null, state, null, null, null, null, null));
			reject(() -> request(null, null, List.of(state), null, null, null, null));
		}
		for (List<String> states : List.of(List.<String>of(), List.of("done", "done"),
				List.of("done", "cancel", "done"), Arrays.asList("done", null))) {
			reject(() -> request(null, null, states, null, null, null, null));
		}
		for (int limit : List.of(0, -1, 1001, Integer.MAX_VALUE)) {
			reject(() -> request(null, null, null, null, null, limit, null));
		}
		for (String sort : List.of("", "ASC", "ascending", "secret-marker")) {
			reject(() -> request(null, null, null, null, null, null, sort));
		}
		for (String market : List.of("", "krw-btc", "KRW", "KRW-BTC&state=wait", "X".repeat(257))) {
			reject(() -> request(market, null, null, null, null, null, null));
		}
		assertThat(request("A-" + "B".repeat(254), null, null, null, null, null, null).market()).hasSize(256);
		reject(() -> request("A-" + "B".repeat(255), null, null, null, null, null, null));
	}

	@Test
	void normalizesOffsetAndEpochMillisToTheSameInstantAndAllowsExactlySevenDays() {
		String start = "2026-07-01T10:00:00+09:00";
		String end = "2026-07-08T01:00:00Z";
		var iso = request(null, null, null, start, end, null, null);
		var epoch = request(null, null, null,
				Long.toString(Instant.parse("2026-07-01T01:00:00Z").toEpochMilli()),
				Long.toString(Instant.parse(end).toEpochMilli()), null, null);
		assertThat(iso).isEqualTo(epoch);
		assertThat(iso.startTime()).isEqualTo("2026-07-01T01:00:00Z");
		assertThat(iso.endTime()).isEqualTo(end);
		assertThat(request(null, null, null, "0", "0", null, null).startTime()).isEqualTo("1970-01-01T00:00:00Z");
		for (String boundary : List.of(Long.toString(Long.MIN_VALUE), Long.toString(Long.MAX_VALUE))) {
			assertThat(request(null, null, null, boundary, boundary, null, null).startTime())
					.isEqualTo(Instant.ofEpochMilli(Long.parseLong(boundary)).toString());
		}
	}

	@Test
	void normalizesEitherBoundaryIndependentlyWithoutCreatingTheOtherBoundary() {
		String utc = "2026-07-01T01:00:00Z";
		for (String time : List.of("2026-07-01T10:00:00+09:00",
				Long.toString(Instant.parse(utc).toEpochMilli()),
				Long.toString(Long.MIN_VALUE), Long.toString(Long.MAX_VALUE))) {
			String expected = time.contains("T") ? utc : Instant.ofEpochMilli(Long.parseLong(time)).toString();
			var startOnly = request(null, null, null, time, null, null, null);
			assertThat(startOnly.startTime()).isEqualTo(expected);
			assertThat(startOnly.endTime()).isNull();
			var endOnly = request(null, null, null, null, time, null, null);
			assertThat(endOnly.startTime()).isNull();
			assertThat(endOnly.endTime()).isEqualTo(expected);
		}
	}

	@Test
	void rejectsInvalidSingleBoundariesAndInvalidRangesWithoutLeakingInput() {
		String valid = "2026-07-01T01:00:00Z";
		for (String time : List.of("2026-07-01T01:00:00", "2026-02-30T01:00:00Z", "9223372036854775808",
				"-9223372036854775809", "1.5", "", "secret-marker", "x".repeat(256), "x".repeat(257))) {
			reject(() -> request(null, null, null, time, valid, null, null));
			reject(() -> request(null, null, null, valid, time, null, null));
			reject(() -> request(null, null, null, time, null, null, null));
			reject(() -> request(null, null, null, null, time, null, null));
		}
		reject(() -> request(null, null, null, valid, "2026-07-01T00:59:59Z", null, null));
		reject(() -> request(null, null, null, valid, "2026-07-08T01:00:00.000000001Z", null, null));
	}

	private static UpbitClosedOrdersRequest request(String market, String state, List<String> states,
			String start, String end, Integer limit, String sort) {
		return new UpbitClosedOrdersRequest(market, state, states, start, end, limit, sort);
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, error -> {
			assertThat(error.getMessage()).isEqualTo("Invalid Upbit closed orders request");
			assertThat(error.getCause()).isNull();
			assertThat(error.getSuppressed()).isEmpty();
			var trace = new StringWriter();
			error.printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain("secret-marker");
		});
	}
}
