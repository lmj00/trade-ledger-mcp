package io.github.lmj.tradeledger.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot;
import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot.Execution;

class BinanceAccountTradesMapperTest {
	private final BinanceAccountTradesMapper mapper = new BinanceAccountTradesMapper();
	private static final String ALIAS = "local-account";
	private static final String SYMBOL = "BTCUSDT";
	private static final String MARKER = "synthetic-private-marker";

	@Test
	void enforcesInclusiveRequestBoundWithoutChangingUnfilteredInt64Contract() {
		for (long id : new long[] {0, 9_007_199_254_740_993L, Long.MAX_VALUE}) {
			var request = new BinanceAccountTradesRequest(SYMBOL, 2, Long.toString(id));
			assertThat(mapper.map(ALIAS, request, array(fill(id))).trades().getFirst().id()).isEqualTo(id);
			assertThat(mapper.map(ALIAS, request, "[]").possiblyTruncated()).isFalse();
			assertSafeFailure(() -> mapper.map(ALIAS, request, array(fill(id - 1))));
			assertSafeFailure(() -> mapper.map(ALIAS, request, array(fill(id), fill(id - 1))));
		}
		var request = new BinanceAccountTradesRequest(SYMBOL, 2, "10");
		assertThat(mapper.map(ALIAS, request, array(fill(10), fill(11))).possiblyTruncated()).isTrue();
		assertThat(mapper.map(ALIAS, request, array(fill(11))).possiblyTruncated()).isFalse();
		assertSafeFailure(() -> mapper.map(ALIAS, request, array(fill(10), fill(10))));
		assertSafeFailure(() -> mapper.map(ALIAS, request, array(fill(10), fill(11), fill(12))));
		assertSafeFailure(() -> mapper.map(ALIAS, (BinanceAccountTradesRequest) null, "[]"));
		assertThat(mapper.map(ALIAS, SYMBOL, array(fill(Long.MIN_VALUE))).trades().getFirst().id())
				.isEqualTo(Long.MIN_VALUE);
	}

	@Test
	void mapsEmptyArrayWithoutClaimingCompleteHistory() {
		var snapshot = mapper.map(ALIAS, SYMBOL, "[]");
		assertThat(snapshot.serviceId()).isEqualTo("binance");
		assertThat(snapshot.accountAlias()).isEqualTo(ALIAS);
		assertThat(snapshot.symbol()).isEqualTo(SYMBOL);
		assertThat(snapshot.trades()).isEmpty();
		assertThat(snapshot.possiblyTruncated()).isFalse();
	}

	@Test
	void preservesEveryFillFieldForBuySellMakerAndDifferentFeeAssets() {
		String buy = raw(fill(9_007_199_254_740_993L), "orderId", "9223372036854775807");
		String sell = raw(raw(raw(raw(raw(fill(Long.MAX_VALUE), "orderId", "9007199254740993"),
				"orderListId", "9007199254740995"), "isBuyer", "false"), "isMaker", "true"),
				"isBestMatch", "false");
		sell = text(text(sell, "commissionAsset", "USDT"), "commission", "0.12000000");
		String third = text(raw(fill(3), "orderListId", "9223372036854775807"), "commissionAsset", "BTC");
		var snapshot = mapper.map(ALIAS, SYMBOL, array(buy, sell, third));
		assertThat(snapshot.trades()).containsExactly(
				new Execution(SYMBOL, 9_007_199_254_740_993L, Long.MAX_VALUE, -1,
						"12345.1234567890123456789000", "0.0000000000000000000200",
						"0.0000000000000002469024691357800", "0.0000000000000000000100", "BNB",
						Instant.parse("2017-07-12T13:19:09.590Z"), true, false, true),
				new Execution(SYMBOL, Long.MAX_VALUE, 9_007_199_254_740_993L, 9_007_199_254_740_995L,
						"12345.1234567890123456789000", "0.0000000000000000000200",
						"0.0000000000000002469024691357800", "0.12000000", "USDT",
						Instant.parse("2017-07-12T13:19:09.590Z"), false, true, false),
				new Execution(SYMBOL, 3, 100234, Long.MAX_VALUE,
						"12345.1234567890123456789000", "0.0000000000000000000200",
						"0.0000000000000002469024691357800", "0.0000000000000000000100", "BTC",
						Instant.parse("2017-07-12T13:19:09.590Z"), true, false, true));
		assertThat(snapshot.possiblyTruncated()).isFalse();
	}

	@Test
	void scopesIdentityByLocalAccountExchangeAndSymbolAndRejectsDuplicatesWithinResponse() {
		var first = mapper.map(ALIAS, SYMBOL, array(fill(1)));
		var second = mapper.map("another-local-account", SYMBOL, array(fill(1)));
		var otherSymbol = mapper.map(ALIAS, "ETHUSDT", array(text(fill(1), "symbol", "ETHUSDT")));
		assertThat(first.trades()).isEqualTo(second.trades());
		assertThat(first.accountAlias()).isNotEqualTo(second.accountAlias());
		assertThat(first.serviceId()).isEqualTo(second.serviceId()).isEqualTo("binance");
		assertThat(first.symbol()).isNotEqualTo(otherSymbol.symbol());
		assertThat(first.trades().getFirst().id()).isEqualTo(otherSymbol.trades().getFirst().id());
		reject(array(fill(1), fill(1)));
		reject(array(fill(1), text(fill(1), "commissionAsset", "USDT")));
		reject(array(fill(1), text(fill(2), "symbol", "ETHUSDT")));
		reject(array(fill(1), text(fill(2), "symbol", "btcusdt")));
		reject(array(fill(1), raw(fill(2), "qty", "null")));
	}

	@Test
	void defaultsTo500AndFlagsOnlyLimitSizedResponsesWhileEnforcingBothCaps() {
		assertThat(BinanceAccountTradesMapper.DEFAULT_LIMIT).isEqualTo(500);
		assertThat(mapper.map(ALIAS, SYMBOL, fills(500)).possiblyTruncated()).isTrue();
		assertThat(mapper.map(ALIAS, SYMBOL, fills(499)).possiblyTruncated()).isFalse();
		reject(fills(501));
		assertThat(mapper.map(ALIAS, SYMBOL, 1, array(fill(1))).possiblyTruncated()).isTrue();
		assertThat(mapper.map(ALIAS, SYMBOL, 1, "[]").possiblyTruncated()).isFalse();
		reject(array(fill(1), fill(2)), 1);
		var maximum = mapper.map(ALIAS, SYMBOL, 1000, fills(1000));
		assertThat(maximum.trades()).hasSize(1000);
		assertThat(maximum.possiblyTruncated()).isTrue();
		assertThat(mapper.map(ALIAS, SYMBOL, 1000, fills(999)).possiblyTruncated()).isFalse();
		reject(fills(1001), 1000);
		for (int limit : new int[] {Integer.MIN_VALUE, -1, 0, 1001, Integer.MAX_VALUE}) {
			reject("[]", limit);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"symbol", "id", "orderId", "orderListId", "price", "qty", "quoteQty",
			"commission", "commissionAsset", "time", "isBuyer", "isMaker", "isBestMatch"})
	void rejectsMissingNullAndContainerFields(String field) {
		String json = fill(1);
		reject(array(json.replace("\"" + field + "\":", "\"omitted\":")));
		for (String value : List.of("null", "{}", "[]", "\"\"", "\"   \"")) {
			reject(array(raw(json, field, value)));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "orderId", "orderListId", "time"})
	void requiresExactInt64NumbersAndConvertsEpochMilliseconds(String field) {
		for (long value : new long[] {Long.MIN_VALUE, -1, 0, 9_007_199_254_740_993L, Long.MAX_VALUE}) {
			var execution = mapper.map(ALIAS, SYMBOL, array(raw(fill(1), field, Long.toString(value))))
					.trades().getFirst();
			switch (field) {
				case "id" -> assertThat(execution.id()).isEqualTo(value);
				case "orderId" -> assertThat(execution.orderId()).isEqualTo(value);
				case "orderListId" -> assertThat(execution.orderListId()).isEqualTo(value);
				case "time" -> assertThat(execution.time()).isEqualTo(Instant.ofEpochMilli(value));
				default -> throw new AssertionError(field);
			}
		}
		for (String value : List.of("9223372036854775808", "-9223372036854775809", "1.0", "1e2",
				"\"9007199254740993\"", "true", "1".repeat(128), "1".repeat(129))) {
			reject(array(raw(fill(1), field, value)));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"isBuyer", "isMaker", "isBestMatch"})
	void requiresBooleanTokens(String field) {
		for (String value : List.of("\"true\"", "\"false\"", "0", "1", "-1", "0.0")) {
			reject(array(raw(fill(1), field, value)));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"price", "qty", "quoteQty", "commission"})
	void preservesDecimalTextAndTrailingZeroesAtLengthBoundary(String field) {
		for (String value : List.of("0", "0.0000", "+001.2300", "1".repeat(128), "1." + "2".repeat(126))) {
			var execution = mapper.map(ALIAS, SYMBOL, array(text(fill(1), field, value))).trades().getFirst();
			String actual = switch (field) {
				case "price" -> execution.price();
				case "qty" -> execution.qty();
				case "quoteQty" -> execution.quoteQty();
				case "commission" -> execution.commission();
				default -> throw new AssertionError(field);
			};
			assertThat(actual).isEqualTo(value);
		}
		for (String value : List.of("1".repeat(129), "1." + "2".repeat(127), "-1", "1e10", "NaN",
				"Infinity", ".1", "1.", " 1", "1 ", "0x10", "1,000", MARKER)) {
			reject(array(text(fill(1), field, value)));
		}
		reject(array(raw(fill(1), field, "1")));
		reject(array(raw(fill(1), field, "true")));
	}

	@Test
	void boundsLocalLabelsSymbolAndCommissionAssetAndRequiresStrings() {
		String boundary = "x".repeat(256);
		var mapped = mapper.map(boundary, boundary, array(text(text(fill(1), "symbol", boundary),
				"commissionAsset", boundary)));
		assertThat(mapped.accountAlias()).isEqualTo(boundary);
		assertThat(mapped.symbol()).isEqualTo(boundary);
		assertThat(mapped.trades().getFirst().commissionAsset()).isEqualTo(boundary);
		String surrogatePairs = "\uD83D\uDE00".repeat(128);
		assertThat(mapper.map(surrogatePairs, SYMBOL, "[]").accountAlias()).hasSize(256);
		for (String value : new String[] {null, "", " ", "x".repeat(257), surrogatePairs + "x"}) {
			assertSafeFailure(() -> mapper.map(value, SYMBOL, "[]"));
			assertSafeFailure(() -> mapper.map(ALIAS, value, "[]"));
		}
		for (String field : List.of("symbol", "commissionAsset")) {
			for (String value : List.of("42", "true", "\"" + "x".repeat(257) + "\"")) {
				reject(array(raw(fill(1), field, value)));
			}
		}
	}

	@Test
	void boundsRawUtf16DocumentBeforeParsing() {
		assertThat(BinanceAccountTradesMapper.MAX_JSON_LENGTH).isEqualTo(1_048_576);
		assertThat(BinanceAccountTradesMapper.MAX_NESTING_DEPTH).isEqualTo(64);
		assertThat(BinanceAccountTradesMapper.MAX_STRING_LENGTH).isEqualTo(256);
		assertThat(BinanceAccountTradesMapper.MAX_NUMBER_LENGTH).isEqualTo(128);
		assertThat(BinanceAccountTradesMapper.MAX_TRADES).isEqualTo(1000);
		int maximum = BinanceAccountTradesMapper.MAX_JSON_LENGTH;
		assertThat(mapper.map(ALIAS, SYMBOL, "[]" + " ".repeat(maximum - 2)).trades()).isEmpty();
		reject("[]" + " ".repeat(maximum - 1));
	}

	@Test
	void boundsDepthIncludingIgnoredArraysAndObjects() {
		for (String open : List.of("[", "{\"nested\":")) {
			String close = open.equals("[") ? "]" : "}";
			// Root array + fill object + 62 nested containers = depth 64.
			String boundary = open.repeat(62) + "0" + close.repeat(62);
			assertThat(mapper.map(ALIAS, SYMBOL, array(extra(fill(1), "unknown", boundary))).trades()).hasSize(1);
			reject(array(extra(fill(1), "unknown", open + boundary + close)));
		}
		reject(array(extra(fill(1), "unknown", "[".repeat(10_000) + "0" + "]".repeat(10_000))));
	}

	@Test
	void boundsDecodedUnknownStringsAndPropertyNamesIncludingEscapes() {
		for (String unit : List.of("x", "\\u0078")) {
			String boundary = array(extra(fill(1), unit.repeat(256), "\"" + unit.repeat(256) + "\""));
			assertThat(mapper.map(ALIAS, SYMBOL, boundary).trades()).hasSize(1);
			reject(array(extra(fill(1), unit.repeat(257), "0")));
			reject(array(extra(fill(1), "unknown", "\"" + unit.repeat(257) + "\"")));
		}
	}

	@Test
	void boundsOriginalNumericTokensBeforeNormalizationInIgnoredFields() {
		for (String number : List.of("1".repeat(128), "1." + "0".repeat(126), "-1." + "0".repeat(125),
				"1." + "0".repeat(122) + "e+00", "1." + "0".repeat(122) + "E-00")) {
			assertThat(number).hasSize(128);
			String over = number.contains(".") ? number.replace(".", ".0") : number + "1";
			assertThat(over).hasSize(129);
			for (String container : List.of("%s", "{\"nested\":[%s]}")) {
				assertThat(mapper.map(ALIAS, SYMBOL, array(extra(fill(1), "unknown", container.formatted(number))))
						.trades()).hasSize(1);
				reject(array(extra(fill(1), MARKER, container.formatted(over))));
			}
		}
	}

	@Test
	void rejectsInvalidJsonDuplicateKeysAndTrailingContentWithSanitizedErrors() {
		String valid = array(fill(1));
		for (String json : new String[] {null, "", "null", "{}", "42", "[", "[null]", "[42]", "[[]]", "[{}]",
				"[{\"" + MARKER + "\":}]", valid + " {}", valid + " []", valid + " " + MARKER,
				valid.substring(0, valid.length() - 1), valid.replace("\"symbol\":", "\"symbol\":\"" + MARKER + "\",\"symbol\":"),
				array(extra(fill(1), "unknown", "{\"a\":1,\"a\":2}")),
				array(extra(fill(1), "unknown", "{\"a\":1,\"\\u0061\":2}")),
				"[" + fill(1) + ",]", "/*" + MARKER + "*/" + valid}) {
			reject(json);
		}
		assertSafeFailure(() -> mapper.map(MARKER, MARKER, array(fill(1))));
		assertSafeFailure(() -> mapper.map(MARKER, SYMBOL, "[{\"" + MARKER + "\":}]"));
	}

	@Test
	void defensivelyCopiesAndExposesAnImmutableList() {
		var mapped = mapper.map(ALIAS, SYMBOL, array(fill(1), fill(2)));
		assertThatThrownBy(() -> mapped.trades().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> mapped.trades().set(0, mapped.trades().get(1)))
				.isInstanceOf(UnsupportedOperationException.class);
		var input = new ArrayList<>(mapped.trades());
		var snapshot = new BinanceAccountTradesSnapshot("binance", ALIAS, SYMBOL, input, false);
		input.clear();
		assertThat(snapshot.trades()).containsExactlyElementsOf(mapped.trades());
		assertThatThrownBy(() -> snapshot.trades().add(mapped.trades().getFirst()))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private void reject(String json) {
		reject(json, 500);
	}

	private void reject(String json, int limit) {
		assertSafeFailure(() -> mapper.map(ALIAS, SYMBOL, limit, json));
	}

	private void assertSafeFailure(ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(IllegalArgumentException.class, error -> {
			assertThat(error.getMessage()).isEqualTo("Invalid Binance account trades response");
			assertThat(error.getCause()).isNull();
			assertThat(error.getSuppressed()).isEmpty();
			var trace = new StringWriter();
			error.printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain(MARKER, ALIAS);
		});
	}

	private static String fill(long id) {
		return """
				{"symbol":"BTCUSDT","id":%d,"orderId":100234,"orderListId":-1,
				"price":"12345.1234567890123456789000","qty":"0.0000000000000000000200",
				"quoteQty":"0.0000000000000002469024691357800","commission":"0.0000000000000000000100",
				"commissionAsset":"BNB","time":1499865549590,"isBuyer":true,"isMaker":false,"isBestMatch":true}
				""".formatted(id).strip();
	}

	private static String fills(int count) {
		return "[" + IntStream.range(0, count).mapToObj(BinanceAccountTradesMapperTest::fill)
				.collect(Collectors.joining(",")) + "]";
	}

	private static String array(String... fills) {
		return "[" + String.join(",", fills) + "]";
	}

	private static String extra(String json, String name, String value) {
		return "{\"" + name + "\":" + value + "," + json.substring(1);
	}

	private static String text(String json, String field, String value) {
		return raw(json, field, "\"" + value + "\"");
	}

	private static String raw(String json, String field, String value) {
		return json.replaceFirst(Pattern.quote("\"" + field + "\":")
				+ "(?:\"[^\"]*\"|-?[0-9]+|true|false)", Matcher.quoteReplacement("\"" + field + "\":" + value));
	}
}
