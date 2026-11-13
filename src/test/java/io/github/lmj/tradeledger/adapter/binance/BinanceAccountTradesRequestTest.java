package io.github.lmj.tradeledger.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class BinanceAccountTradesRequestTest {
	private static final String MARKER = "synthetic-request-marker";

	@Test
	void defaultsTo500AndAcceptsBothLimitBoundaries() {
		var request = new BinanceAccountTradesRequest("BTCUSDT");
		assertThat(request.symbol()).isEqualTo("BTCUSDT");
		assertThat(request.limit()).isEqualTo(500);
		assertThat(request.toQueryString(0))
				.isEqualTo("symbol=BTCUSDT&limit=500&recvWindow=5000&timestamp=0");
		for (int limit : new int[] {1, 1000}) {
			var boundary = new BinanceAccountTradesRequest("BTCUSDT", limit);
			assertThat(boundary.limit()).isEqualTo(limit);
			assertThat(boundary.toQueryString(123))
					.isEqualTo("symbol=BTCUSDT&limit=" + limit + "&recvWindow=5000&timestamp=123");
		}
		for (int limit : new int[] {Integer.MIN_VALUE, -1, 0, 1001, Integer.MAX_VALUE}) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest(MARKER, limit));
		}
	}

	@Test
	void rejectsNullBlankAndMalformedSurrogatesWithoutExposingInput() {
		for (String symbol : new String[] {null, "", " ", "\t\r\n", "\u2003\u3000",
				MARKER + "\uD800", "\uDC00" + MARKER, MARKER + "\uD800x",
				MARKER + "\uD800\uD800", MARKER + "\uDC00\uD800"}) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest(symbol));
		}
	}

	@Test
	void rejectsEveryIsoControlEvenInsideOtherwiseValidSymbols() {
		for (int character = 0; character <= 0x9f; character++) {
			if (character <= 0x1f || character >= 0x7f) {
				String symbol = MARKER + (char) character + "BTC";
				assertSafeFailure(() -> new BinanceAccountTradesRequest(symbol));
			}
		}
	}

	@Test
	void boundsUtf16CodeUnitsAndPreservesValidUnicode() {
		for (String boundary : List.of("x".repeat(256), "\uD83D\uDE00".repeat(128),
				"x".repeat(254) + "\uD83D\uDE00")) {
			var request = new BinanceAccountTradesRequest(boundary);
			assertThat(request.symbol()).isEqualTo(boundary).hasSize(256);
			assertSafeFailure(() -> new BinanceAccountTradesRequest(boundary + "x"));
		}
		assertSafeFailure(() -> new BinanceAccountTradesRequest("x".repeat(255) + "\uD83D\uDE00"));
		var unicode = new BinanceAccountTradesRequest("한é\uD83D\uDE00");
		assertThat(unicode.symbol()).isEqualTo("한é\uD83D\uDE00");
		assertThat(unicode.toQueryString(1)).isEqualTo(
				"symbol=%ED%95%9C%C3%A9%F0%9F%98%80&limit=500&recvWindow=5000&timestamp=1");
	}

	@Test
	void preservesCaseAndSpacesAndEncodesReservedCharactersOnce() {
		var request = new BinanceAccountTradesRequest(" btcUsdt-._~ !*'();:@&=+$,/?#[]% ");
		assertThat(request.symbol()).isEqualTo(" btcUsdt-._~ !*'();:@&=+$,/?#[]% ");
		assertThat(request.toQueryString(42)).isEqualTo(
				"symbol=%20btcUsdt-._~%20%21%2A%27%28%29%3B%3A%40%26%3D%2B%24%2C%2F%3F%23%5B%5D%25%20"
						+ "&limit=500&recvWindow=5000&timestamp=42");
		// Input is raw symbol text: a literal percent escape must stay literal on decode.
		assertThat(new BinanceAccountTradesRequest("BTC%2FUSDT").toQueryString(0))
				.isEqualTo("symbol=BTC%252FUSDT&limit=500&recvWindow=5000&timestamp=0");
	}

	@Test
	void preventsSymbolFromInjectingDuplicateParametersOrOtherFilters() {
		var request = new BinanceAccountTradesRequest(
				"BTC&symbol=ETH&limit=1&recvWindow=1&timestamp=2&signature=x&fromId=3");
		String query = request.toQueryString(7);
		assertThat(query).isEqualTo("symbol=BTC%26symbol%3DETH%26limit%3D1%26recvWindow%3D1"
				+ "%26timestamp%3D2%26signature%3Dx%26fromId%3D3&limit=500&recvWindow=5000&timestamp=7");
		assertThat(query.split("&")).containsExactly(
				"symbol=BTC%26symbol%3DETH%26limit%3D1%26recvWindow%3D1%26timestamp%3D2%26signature%3Dx%26fromId%3D3",
				"limit=500", "recvWindow=5000", "timestamp=7");
	}

	@Test
	void serializesExactNonnegativeInt64MillisecondsWithoutReadingAClock() {
		var request = new BinanceAccountTradesRequest("BTCUSDT");
		assertThat(request.toQueryString(0)).endsWith("&timestamp=0");
		assertThat(request.toQueryString(9_007_199_254_740_993L)).isEqualTo(
				"symbol=BTCUSDT&limit=500&recvWindow=5000&timestamp=9007199254740993");
		assertThat(request.toQueryString(Long.MAX_VALUE)).isEqualTo(
				"symbol=BTCUSDT&limit=500&recvWindow=5000&timestamp=9223372036854775807");
		assertThat(request.toQueryString(0)).isEqualTo(request.toQueryString(0));
		assertSafeFailure(() -> request.toQueryString(-1));
		assertSafeFailure(() -> request.toQueryString(Long.MIN_VALUE));
	}

	@Test
	void exposesOnlyRequestInputsAndRetainsOnlyImmutableRequestInput() {
		Class<BinanceAccountTradesRequest> type = BinanceAccountTradesRequest.class;
		assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
		assertThat(Arrays.stream(type.getDeclaredConstructors())
				.map(constructor -> Arrays.asList(constructor.getParameterTypes())).toList())
				.containsExactlyInAnyOrder(List.of(String.class), List.of(String.class, int.class),
						List.of(String.class, int.class, String.class),
						List.of(String.class, int.class, String.class, String.class, String.class),
						List.of(String.class, int.class, String.class, String.class, String.class, String.class));
		assertThat(Arrays.stream(type.getDeclaredFields()).map(field -> field.getName()).toList())
				.containsExactlyInAnyOrder("symbol", "limit", "fromId", "startTime", "endTime", "orderId");
		assertThat(Arrays.stream(type.getDeclaredFields())
				.allMatch(field -> Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())))
				.isTrue();
		assertThat(Arrays.stream(type.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers())).map(method -> method.getName()).toList())
				.containsExactlyInAnyOrder("symbol", "limit", "fromId", "startTime", "endTime", "orderId", "toQueryString");
	}

	@Test
	void preservesOrderIdBoundariesAloneAndWithFromIdAndOmittedQueries() {
		for (String id : List.of("0", "9007199254740993", "9223372036854775807")) {
			for (boolean withFromId : List.of(false, true)) {
				var request = new BinanceAccountTradesRequest("BTCUSDT", 2, withFromId ? id : null, null, null, id);
				assertThat(request.orderId()).isEqualTo(Long.valueOf(id));
				assertThat(request.toQueryString(123)).isEqualTo("symbol=BTCUSDT&limit=2"
						+ (withFromId ? "&fromId=" + id : "") + "&orderId=" + id + "&recvWindow=5000&timestamp=123");
			}
		}
		for (String[] filters : new String[][] {{null, null, null}, {"0", null, null},
				{null, "0", null}, {null, null, "1"}, {null, "0", "1"}}) {
			var legacy = new BinanceAccountTradesRequest("BTCUSDT", 2, filters[0], filters[1], filters[2]);
			var omitted = new BinanceAccountTradesRequest("BTCUSDT", 2, filters[0], filters[1], filters[2], null);
			assertThat(legacy.orderId()).isNull();
			assertThat(omitted.toQueryString(123)).isEqualTo(legacy.toQueryString(123));
		}
	}

	@Test
	void rejectsInvalidOrderIdsAndEachTimeCombinationSafely() {
		for (String id : List.of("", " ", "\t", "-1", "-0", "+1", "1.0", "1e3", "00", "01",
				" 1", "1 ", "１", "١", "9223372036854775808", "1".repeat(10000), MARKER)) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, null, null, null, id));
		}
		for (String[] bounds : new String[][] {{"0", null}, {null, "0"}, {"0", "0"}}) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, null, bounds[0], bounds[1], "0"));
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, "0", bounds[0], bounds[1], "0"));
		}
	}

	@Test
	void preservesCanonicalFromIdBoundariesAndOmittedQuery() {
		for (String id : List.of("0", "9007199254740993", "9223372036854775807")) {
			var request = new BinanceAccountTradesRequest("BTCUSDT", 1000, id);
			assertThat(request.fromId()).isEqualTo(Long.valueOf(id));
			assertThat(request.toQueryString(123)).isEqualTo("symbol=BTCUSDT&limit=1000&fromId="
					+ id + "&recvWindow=5000&timestamp=123");
		}
		assertThat(new BinanceAccountTradesRequest("BTCUSDT", 500, null).toQueryString(0))
				.isEqualTo(new BinanceAccountTradesRequest("BTCUSDT").toQueryString(0));
		assertThat(new BinanceAccountTradesRequest("BTCUSDT").fromId()).isNull();
	}

	@Test
	void rejectsNoncanonicalOrOutOfRangeFromIdsSafely() {
		for (String id : List.of("", " ", "\t", "-1", "-0", "+1", "1.0", "1e3", "00", "01",
				" 1", "1 ", "１", "١", "9223372036854775808", "1".repeat(10000), MARKER)) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 500, id));
		}
	}

	@Test
	void preservesOptionalTimeBoundsAndChecksDurationWithoutOverflow() {
		for (String value : List.of("0", "9007199254740993", "9223372036854775807")) {
			for (int mode = 0; mode < 3; mode++) {
				String start = mode == 1 ? null : value;
				String end = mode == 0 ? null : value;
				var request = new BinanceAccountTradesRequest("BTCUSDT", 2, null, start, end);
				assertThat(request.startTime()).isEqualTo(start == null ? null : Long.valueOf(start));
				assertThat(request.endTime()).isEqualTo(end == null ? null : Long.valueOf(end));
				assertThat(request.toQueryString(123)).isEqualTo("symbol=BTCUSDT&limit=2"
						+ (start == null ? "" : "&startTime=" + start)
						+ (end == null ? "" : "&endTime=" + end) + "&recvWindow=5000&timestamp=123");
			}
		}
		for (long start : new long[] {0, Long.MAX_VALUE - 86_400_000L}) {
			assertThat(new BinanceAccountTradesRequest("BTCUSDT", 2, null, Long.toString(start),
					Long.toString(start + 86_400_000L)).endTime()).isEqualTo(start + 86_400_000L);
		}
		for (String[] bounds : new String[][] {{"1", "0"}, {"0", "86400001"},
				{"0", "9223372036854775807"}, {"9223372036768375806", "9223372036854775807"}}) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, null, bounds[0], bounds[1]));
		}
		for (String[] bounds : new String[][] {{"0", null}, {null, "0"}, {"0", "0"}}) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, "0", bounds[0], bounds[1]));
		}
	}

	@Test
	void rejectsMalformedTimeBoundsBeforeParsing() {
		for (String value : List.of("", " ", "\t", "-1", "-0", "+1", "1.0", "1e3", "00", "01",
				" 1", "1 ", "１", "١", "9223372036854775808", "1".repeat(10000), MARKER)) {
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, null, value, null));
			assertSafeFailure(() -> new BinanceAccountTradesRequest("BTCUSDT", 2, null, null, value));
		}
	}

	private static void assertSafeFailure(ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalid Binance account trades request").hasNoCause()
				.satisfies(exception -> {
					assertThat(exception.getSuppressed()).isEmpty();
					StringWriter stack = new StringWriter();
					exception.printStackTrace(new PrintWriter(stack));
					assertThat(stack.toString()).doesNotContain(MARKER);
				});
	}
}
