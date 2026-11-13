package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.lmj.tradeledger.application.model.ClosedOrdersSnapshot;

class UpbitClosedOrdersMapperTest {
	private final UpbitClosedOrdersMapper mapper = new UpbitClosedOrdersMapper();
	private final UpbitClosedOrdersRequest defaults = new UpbitClosedOrdersRequest();

	@Test
	void mapsEmptyArrayWithoutClaimingHistoryCompleteness() {
		var snapshot = mapper.map("[]", defaults);
		assertThat(snapshot.serviceId()).isEqualTo("upbit");
		assertThat(snapshot.orders()).isEmpty();
		assertThat(snapshot.possiblyTruncated()).isFalse();
	}

	@Test
	void preservesClosedOrderSummariesWithoutRequiringTradesOrInferringFeeCurrency() {
		String cancelled = order(2, "cancel");
		String unfilled = raw(raw(raw(order(3, "cancel"), "executed_volume", "\"0\""),
				"paid_fee", "\"0\""), "trades_count", "0");
		var snapshot = mapper.map("upbit-service", array(order(1, "done"), cancelled, unfilled), defaults);
		assertThat(snapshot.serviceId()).isEqualTo("upbit-service");
		assertThat(snapshot.orders()).hasSize(3);
		var done = snapshot.orders().getFirst();
		assertThat(done.orderId()).isEqualTo(id(1));
		assertThat(done.market()).isEqualTo("KRW-BTC");
		assertThat(done.side()).isEqualTo("bid");
		assertThat(done.orderType()).isEqualTo("limit");
		assertThat(done.state()).isEqualTo("done");
		assertThat(done.createdAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
		assertThat(done.executedVolume()).isEqualTo("0.0000000000000000000200");
		assertThat(done.paidFee()).isEqualTo("0.0000000000000000000100");
		assertThat(done.tradesCount()).isEqualTo(new BigInteger("18446744073709551616"));
		assertThat(done.feeCurrency()).isEmpty();
		assertThat(snapshot.orders().get(1).state()).isEqualTo("cancel");
		assertThat(snapshot.orders().get(1).executedVolume()).isEqualTo(done.executedVolume());
		assertThat(snapshot.orders().get(1).paidFee()).isEqualTo(done.paidFee());
		assertThat(snapshot.orders().get(2).state()).isEqualTo("cancel");
		assertThat(snapshot.orders().get(2).executedVolume()).isEqualTo("0");
		assertThat(snapshot.orders().get(2).paidFee()).isEqualTo("0");
		assertThat(snapshot.orders().get(2).tradesCount()).isEqualTo(BigInteger.ZERO);
		assertThatThrownBy(() -> snapshot.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
		var input = new ArrayList<>(snapshot.orders());
		var copy = new ClosedOrdersSnapshot("upbit", input, false);
		input.clear();
		assertThat(copy.orders()).hasSize(3);
	}

	@Test
	void requiresExactRequestedMarketAndRejectsTheWholeResponseOnLaterMismatch() {
		var request = new UpbitClosedOrdersRequest("KRW-BTC", null, null, null, null, null, null);
		String matching = order(1, "done");
		assertThat(mapper.map(array(matching), request).orders()).extracting("market")
				.containsExactly("KRW-BTC");
		for (String market : List.of("KRW-ETH", "krw-btc", "KRW-BTC ", "secret-marker")) {
			String mismatch = text(order(2, "cancel"), "market", market);
			reject(array(mismatch), request);
			reject(array(matching, mismatch), request);
		}
		assertThat(mapper.map(array(matching, text(order(2, "cancel"), "market", "KRW-ETH")), defaults)
				.orders()).extracting("market").containsExactly("KRW-BTC", "KRW-ETH");
	}

	@ParameterizedTest
	@ValueSource(strings = {"done", "cancel"})
	void requiresRequestedStateForBothScalarAndSingleElementSelections(String state) {
		String matching = order(1, state);
		String mismatch = order(2, state.equals("done") ? "cancel" : "done");
		for (var request : List.of(
				new UpbitClosedOrdersRequest(null, state, null, null, null, null, null),
				new UpbitClosedOrdersRequest(null, null, List.of(state), null, null, null, null))) {
			var accepted = mapper.map(array(matching), request).orders().getFirst();
			assertThat(accepted.state()).isEqualTo(state);
			assertThat(accepted.executedVolume()).isEqualTo("0.0000000000000000000200");
			reject(array(mismatch), request);
			reject(array(matching, mismatch), request);
		}
	}

	@Test
	void preservesMixedStatesForDefaultAndBothExplicitStateOrders() {
		String json = array(order(1, "done"), order(2, "cancel"));
		for (var request : List.of(defaults,
				new UpbitClosedOrdersRequest(null, null, List.of("done", "cancel"), null, null, null, null),
				new UpbitClosedOrdersRequest(null, null, List.of("cancel", "done"), null, null, null, null))) {
			assertThat(mapper.map(json, request).orders()).extracting("state").containsExactly("done", "cancel");
		}
	}

	@Test
	void flagsOnlyLimitSizedResultsAndEnforcesBothItemCaps() {
		var one = request(1);
		assertThat(mapper.map(array(order(1, "done")), one).possiblyTruncated()).isTrue();
		assertThat(mapper.map("[]", one).possiblyTruncated()).isFalse();
		reject(array(order(1, "done"), order(2, "done")), one);
		String hundred = orders(100);
		assertThat(mapper.map(hundred, defaults).possiblyTruncated()).isTrue();
		assertThat(mapper.map(orders(99), defaults).possiblyTruncated()).isFalse();
		reject(orders(101));
		var thousand = mapper.map(orders(1000), request(1000));
		assertThat(thousand.orders()).hasSize(1000);
		assertThat(thousand.possiblyTruncated()).isTrue();
		reject(orders(1001), request(1000));
	}

	@Test
	void rejectsNoncanonicalAndDuplicateUuidsIncludingCaseVariantsAndInvalidLaterItems() {
		String json = order(1, "done");
		for (String uuid : List.of("1-1-1-1-1", "secret-marker", id(1).replace("-", ""),
				" " + id(1), id(1) + " ")) {
			reject(array(text(json, "uuid", uuid)));
		}
		reject(array(json, json));
		String lower = "abcdefab-abcd-abcd-abcd-abcdefabcdef";
		reject(array(text(json, "uuid", lower), text(json, "uuid", lower.toUpperCase(java.util.Locale.ROOT))));
		reject(array(json, text(order(2, "cancel"), "paid_fee", "secret-marker")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"uuid", "market", "side", "ord_type", "state", "created_at", "executed_volume", "paid_fee", "trades_count"})
	void rejectsMissingNullAndIncorrectlyTypedFields(String field) {
		String json = order(1, "done");
		reject(array(json.replace("\"" + field + "\":", "\"omitted\":")));
		for (String value : List.of("null", "{}", "[]", "true", "\"\"", "\"   \"")) {
			reject(array(raw(json, field, value)));
		}
		reject(array(raw(json, field, field.equals("trades_count") ? "\"3\"" : "42")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"executed_volume", "paid_fee"})
	void preservesDecimalScaleAndEnforcesSyntaxAndLength(String field) {
		for (String value : List.of("0", "1".repeat(128), "1." + "2".repeat(126), "+1." + "2".repeat(125))) {
			var order = mapper.map(array(text(order(1, "done"), field, value)), defaults).orders().getFirst();
			assertThat(field.equals("paid_fee") ? order.paidFee() : order.executedVolume())
					.isEqualTo(value.startsWith("+") ? value.substring(1) : value);
		}
		for (String value : List.of("1".repeat(129), "1." + "2".repeat(127), "-1", "1e10", "NaN",
				"Infinity", ".1", "1.", " 1", "1 ", "0x10", "1,000", "secret-marker")) {
			reject(array(text(order(1, "done"), field, value)));
		}
	}

	@Test
	void requiresLosslessNonnegativeIntegerCountsWithExplicitLengthCap() {
		String json = order(1, "done");
		for (String count : List.of("0", "1".repeat(128))) {
			assertThat(mapper.map(array(raw(json, "trades_count", count)), defaults).orders().getFirst().tradesCount())
					.isEqualTo(new BigInteger(count));
		}
		for (String count : List.of("-1", "1.0", "1e2", "1".repeat(129))) {
			reject(array(raw(json, "trades_count", count)));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"market", "side", "ord_type"})
	void enforcesTextLengthBoundaries(String field) {
		assertThat(mapper.map(array(text(order(1, "done"), field, "x".repeat(256))), defaults).orders()).hasSize(1);
		reject(array(text(order(1, "done"), field, "x".repeat(257))));
	}

	@Test
	void enforcesServiceAndPreparseDocumentLengthBoundaries() {
		assertThat(mapper.map("x".repeat(256), "[]", defaults).serviceId()).hasSize(256);
		for (String service : List.of("", " ", "x".repeat(257))) {
			assertThatThrownBy(() -> mapper.map(service, "[]", defaults)).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Invalid Upbit closed orders response").hasNoCause();
		}
		assertThatThrownBy(() -> mapper.map(null, "[]", defaults)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mapper.map("[]", null)).isInstanceOf(IllegalArgumentException.class);
		int maximum = UpbitClosedOrdersMapper.MAX_JSON_LENGTH;
		assertThat(mapper.map("[]" + " ".repeat(maximum - 2), defaults).orders()).isEmpty();
		reject("[]" + " ".repeat(maximum - 1));
		assertThat(maximum).isEqualTo(1024 * 1024);
		assertThat(UpbitClosedOrdersMapper.MAX_STRING_LENGTH).isEqualTo(256);
		assertThat(UpbitClosedOrdersMapper.MAX_NUMBER_LENGTH).isEqualTo(128);
		assertThat(UpbitClosedOrdersMapper.MAX_ORDERS).isEqualTo(1000);
	}

	@Test
	void requiresClosedStatesAndValidOffsetTimes() {
		for (String state : List.of("wait", "watch", "DONE", "secret-marker")) {
			reject(array(order(1, state)));
		}
		for (String time : List.of("2026-07-01T01:00:00Z", "2026-06-30T20:00:00-05:00")) {
			assertThat(mapper.map(array(text(order(1, "done"), "created_at", time)), defaults).orders().getFirst().createdAt())
					.isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
		}
		for (String time : List.of("2026-07-01T01:00:00", "2026-02-30T01:00:00Z", "secret-marker",
				"x".repeat(256), "x".repeat(257))) {
			reject(array(text(order(1, "done"), "created_at", time)));
		}
	}

	@Test
	void rejectsMalformedDuplicateAndTrailingJsonWithoutRetainingSensitiveDiagnostics() {
		String valid = array(order(1, "done"));
		for (String json : List.of("", "null", "{}", "42", "[", "[null]", "[42]", "[[]]", "[{}]",
				"[{\"secret-marker\":}]", valid + " {}", valid + " garbage",
				valid.replace("\"market\":", "\"market\":\"secret-marker\",\"market\":"),
				valid.replace("\"market\":", "\"unknown\":{\"a\":1,\"a\":2},\"market\":"),
				valid.replace("\"market\":", "\"unknown\":\"" + "x".repeat(257) + "\",\"market\":"))) {
			reject(json);
		}
		reject(null);
	}

	@Test
	void boundsUnknownPropertyNamesAndValuesToo() {
		String json = array(order(1, "done"));
		String atLimit = json.replace("\"market\":", "\"" + "x".repeat(256) + "\":\""
				+ "y".repeat(256) + "\",\"market\":");
		assertThat(mapper.map(atLimit, defaults).orders()).hasSize(1);
		reject(json.replace("\"market\":", "\"" + "x".repeat(257) + "\":0,\"market\":"));
		reject(json.replace("\"market\":", "\"unknown\":" + "1".repeat(129) + ",\"market\":"));
	}

	@Test
	void boundsOriginalNumericTokensBeforeDecimalAndExponentNormalization() {
		String json = array(order(1, "done"));
		for (String boundary : List.of("1".repeat(128), "1." + "0".repeat(126),
				"-1." + "0".repeat(125), "1." + "0".repeat(122) + "e+00",
				"1." + "0".repeat(122) + "E-00")) {
			assertThat(boundary).hasSize(UpbitClosedOrdersMapper.MAX_NUMBER_LENGTH);
			String overLimit = boundary.contains(".") ? boundary.replace(".", ".0") : boundary + "1";
			assertThat(overLimit).hasSize(UpbitClosedOrdersMapper.MAX_NUMBER_LENGTH + 1);
			for (String container : List.of("%s", "{\"nested\":[%s]}")) {
				String atLimit = json.replace("\"market\":", "\"secret-marker\":"
						+ container.formatted(boundary) + ",\"market\":");
				assertThat(mapper.map(atLimit, defaults).orders()).hasSize(1);
				reject(json.replace("\"market\":", "\"secret-marker\":"
						+ container.formatted(overLimit) + ",\"market\":"));
			}
		}
	}

	private void reject(String json) {
		reject(json, defaults);
	}

	private void reject(String json, UpbitClosedOrdersRequest request) {
		assertThatThrownBy(() -> mapper.map(json, request)).isInstanceOfSatisfying(IllegalArgumentException.class, error -> {
			assertThat(error.getMessage()).isEqualTo("Invalid Upbit closed orders response");
			assertThat(error.getCause()).isNull();
			assertThat(error.getSuppressed()).isEmpty();
			var trace = new StringWriter();
			error.printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain("secret-marker");
		});
	}

	private static UpbitClosedOrdersRequest request(int limit) {
		return new UpbitClosedOrdersRequest(null, null, null, null, null, limit, null);
	}

	private static String id(int index) {
		return "00000000-0000-0000-0000-%012d".formatted(index);
	}

	private static String order(int index, String state) {
		return """
				{"uuid":"%s","market":"KRW-BTC","side":"bid","ord_type":"limit","state":"%s",
				"created_at":"2026-07-01T10:00:00+09:00","executed_volume":"0.0000000000000000000200",
				"paid_fee":"0.0000000000000000000100","trades_count":18446744073709551616}
				""".formatted(id(index), state);
	}

	private static String orders(int count) {
		return "[" + IntStream.range(0, count).mapToObj(i -> order(i, "done")).collect(Collectors.joining(",")) + "]";
	}

	private static String array(String... orders) {
		return "[" + String.join(",", orders) + "]";
	}

	private static String text(String json, String field, String value) {
		return raw(json, field, "\"" + value + "\"");
	}

	private static String raw(String json, String field, String value) {
		return json.replaceFirst(Pattern.quote("\"" + field + "\":") + "(?:\"[^\"]*\"|[0-9]+)",
				Matcher.quoteReplacement("\"" + field + "\":" + value));
	}
}
