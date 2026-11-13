package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UpbitOrderMapperTest {

	private final UpbitOrderMapper mapper = new UpbitOrderMapper();

	@Test
	void preservesOrderAndActualFillsWithoutAllocatingFeeOrUsingOrderPrice() {
		String first = fill("fill-1");
		String second = withText(fill("fill-2"), "price", "234.567890123456789012345678900");
		var snapshot = mapper.map("upbit-service", order("done", first + "," + second));

		assertThat(snapshot.serviceId()).isEqualTo("upbit-service");
		assertThat(snapshot.orderId()).isEqualTo("order-1");
		assertThat(snapshot.market()).isEqualTo("KRW-BTC");
		assertThat(snapshot.side()).isEqualTo("bid");
		assertThat(snapshot.orderType()).isEqualTo("limit");
		assertThat(snapshot.state()).isEqualTo("done");
		assertThat(snapshot.createdAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
		assertThat(snapshot.executedVolume()).isEqualTo("0.0000000000000000000200");
		assertThat(snapshot.paidFee()).isEqualTo("0.0000000000000000000100");
		assertThat(snapshot.feeCurrency()).isEmpty();
		assertThat(snapshot.trades()).hasSize(2);
		var execution = snapshot.trades().getFirst();
		assertThat(execution.tradeId()).isEqualTo("fill-1");
		assertThat(execution.market()).isEqualTo("KRW-BTC");
		assertThat(execution.side()).isEqualTo("bid");
		assertThat(execution.price()).isEqualTo("123.456789012345678901234567890");
		assertThat(execution.volume()).isEqualTo("0.0000000000000000000100");
		assertThat(execution.funds()).isEqualTo("0.0000000000000000012345678900");
		assertThat(execution.createdAt()).isEqualTo(snapshot.createdAt());
		assertThat(snapshot.trades().get(1).price()).isEqualTo("234.567890123456789012345678900");
		assertThatThrownBy(() -> snapshot.trades().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void distinguishesCancelledFilledAndUnfilledOrdersWithoutInventingExecutions() {
		var cancelled = mapper.map(order("cancel", fill("fill-1")));
		assertThat(cancelled.state()).isEqualTo("cancel");
		assertThat(cancelled.trades()).hasSize(1);
		assertThat(cancelled.serviceId()).isEqualTo("upbit");
		for (String state : List.of("wait", "cancel")) {
			String json = withText(withText(order(state, ""), "executed_volume", "0"), "paid_fee", "0");
			// Market orders can have null price; fills must never fall back to it.
			json = withRaw(json, "price", "null");
			var unfilled = mapper.map(json);
			assertThat(unfilled.state()).isEqualTo(state);
			assertThat(unfilled.executedVolume()).isEqualTo("0");
			assertThat(unfilled.paidFee()).isEqualTo("0");
			assertThat(unfilled.trades()).isEmpty();
		}
	}

	@ParameterizedTest
	@CsvSource({"market,KRW-BTC,KRW-ETH", "market,KRW-ETH,KRW-BTC",
			"side,bid,ask", "side,ask,bid", "market,KRW-BTC,krw-btc", "side,bid,BID",
			"market,KRW-BTC,secret-marker", "side,bid,secret-marker"})
	void rejectsMismatchedFillFieldsEvenAfterAValidFill(String field, String orderValue, String tradeValue) {
		String valid = withText(fill("first"), field, orderValue);
		String mismatch = withText(fill("second"), field, tradeValue);
		assertThat(mapper.map(withText(order("done", valid), field, orderValue)).trades()).hasSize(1);
		for (String trades : List.of(mismatch, valid + "," + mismatch)) {
			reject(withText(order("done", trades), field, orderValue));
		}
	}

	@ParameterizedTest
	@CsvSource({"bid,done", "ask,done", "bid,cancel", "ask,cancel", "custom,done"})
	void acceptsMatchingSidesWithoutAddingAnEnumerationRestriction(String side, String state) {
		var snapshot = mapper.map(withText(order(state, withText(fill("fill-1"), "side", side)), "side", side));
		assertThat(snapshot.side()).isEqualTo(side);
		assertThat(snapshot.state()).isEqualTo(state);
		assertThat(snapshot.trades()).hasSize(1);
		assertThat(snapshot.trades().getFirst().side()).isEqualTo(side);
	}

	@Test
	void rejectsDuplicateIdsAndInvalidLaterFillWithoutReturningPartialResults() {
		reject(order("done", fill("same") + "," + fill("same")));
		reject(order("done", fill("first") + "," + withText(fill("second"), "price", "broken")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"uuid", "market", "side", "ord_type", "state", "created_at", "executed_volume", "paid_fee"})
	void rejectsMissingNullWrongTypeOrBlankOrderFields(String field) {
		String json = order("done", fill("fill-1"));
		reject(json.replaceFirst(Pattern.quote("\"" + field + "\":"), "\"omitted\":"));
		for (String value : List.of("null", "42", "{}", "[]", "true", "\"\"", "\"   \"")) {
			reject(withRaw(json, field, value));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"uuid", "market", "side", "price", "volume", "funds", "created_at"})
	void rejectsMissingNullWrongTypeOrBlankFillFields(String field) {
		String trade = fill("fill-1");
		reject(order("done", trade.replaceFirst(Pattern.quote("\"" + field + "\":"), "\"omitted\":")));
		for (String value : List.of("null", "42", "{}", "[]", "true", "\"\"", "\"   \"")) {
			reject(order("done", withRaw(trade, field, value)));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"executed_volume", "paid_fee", "price", "volume", "funds"})
	void enforcesDecimalSyntaxAnd128CharacterPolicyForEveryAmount(String field) {
		for (String value : List.of("1".repeat(128), "1." + "2".repeat(126), "+1." + "2".repeat(125))) {
			var snapshot = mapper.map(amount(field, value));
			String actual = switch (field) {
				case "executed_volume" -> snapshot.executedVolume();
				case "paid_fee" -> snapshot.paidFee();
				case "price" -> snapshot.trades().getFirst().price();
				case "volume" -> snapshot.trades().getFirst().volume();
				case "funds" -> snapshot.trades().getFirst().funds();
				default -> throw new AssertionError(field);
			};
			assertThat(actual).isEqualTo(value.startsWith("+") ? value.substring(1) : value);
		}
		for (String value : List.of("1".repeat(129), "1." + "2".repeat(127), "+1." + "2".repeat(126),
				"NaN", "Infinity", "1e10", "1e999999999", "0x10", "1,000", "1.", ".1", " 1", "1 ", "-1")) {
			reject(amount(field, value));
		}
		if (List.of("price", "volume", "funds").contains(field)) {
			reject(amount(field, "0"));
		}
		else {
			mapper.map(amount(field, "0"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"uuid", "market", "side", "ord_type", "state"})
	void enforces256CharacterOrderStringPolicy(String field) {
		String json = order("done", "");
		mapper.map(withText(json, field, "x".repeat(256)));
		reject(withText(json, field, "x".repeat(257)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"uuid", "market", "side"})
	void enforces256CharacterFillStringPolicy(String field) {
		String json = order("done", withText(fill("fill-1"), field, "x".repeat(256)));
		if (List.of("market", "side").contains(field)) {
			json = withText(json, field, "x".repeat(256));
		}
		mapper.map(json);
		reject(order("done", withText(fill("fill-1"), field, "x".repeat(257))));
	}

	@Test
	void enforcesServiceIdentifierPolicy() {
		assertThat(mapper.map("x".repeat(256), order("wait", "")).serviceId()).hasSize(256);
		for (String id : List.of("", " ", "x".repeat(257))) {
			assertThatThrownBy(() -> mapper.map(id, order("wait", ""))).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> mapper.map(null, order("wait", ""))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresValidOffsetTimesAtBothLevelsIncludingLengthBoundaries() {
		for (String time : List.of("2026-07-01T01:00:00Z", "2026-06-30T20:00:00-05:00")) {
			var snapshot = mapper.map(withText(order("done", withText(fill("fill-1"), "created_at", time)),
					"created_at", time));
			assertThat(snapshot.createdAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
			assertThat(snapshot.trades().getFirst().createdAt()).isEqualTo(snapshot.createdAt());
		}
		// 256 characters meets the length policy, but must still be a valid offset timestamp.
		for (String time : List.of("2026-07-01T01:00:00", "2026-02-30T01:00:00Z", "broken",
				"x".repeat(256), "x".repeat(257))) {
			reject(withText(order("done", ""), "created_at", time));
			reject(order("done", withText(fill("fill-1"), "created_at", time)));
		}
	}

	@Test
	void accepts1000FillsAndRejects1001() {
		String trades = IntStream.range(0, 1000).mapToObj(i -> fill("fill-" + i)).collect(Collectors.joining(","));
		assertThat(mapper.map(order("done", trades)).trades()).hasSize(1000);
		reject(order("done", trades + "," + fill("fill-1000")));
		assertThat(UpbitOrderMapper.MAX_DECIMAL_LENGTH).isEqualTo(128);
		assertThat(UpbitOrderMapper.MAX_STRING_LENGTH).isEqualTo(256);
		assertThat(UpbitOrderMapper.MAX_TRADES).isEqualTo(1000);
	}

	@Test
	void rejectsMalformedDocumentsAndMissingOrInvalidTradesWithoutLeakingPayload() {
		for (String json : List.of("", "null", "[]", "42", "{}", "{", "{\"secret-marker\":}",
				order("wait", "") + " {}", order("wait", "") + " garbage",
				order("wait", "").replace("\"trades\":[]", "\"omitted\":[]"),
				order("wait", "").replace("\"trades\":[]", "\"trades\":null"),
				order("wait", "").replace("\"trades\":[]", "\"trades\":{}"),
				order("done", "null"), order("done", "42"), order("done", "[]"),
				order("wait", "").replace("\"uuid\":\"order-1\"", "\"uuid\":\"order-1\",\"uuid\":\"other\""))) {
			reject(json);
		}
		reject(null);
		reject(withText(order("wait", ""), "created_at", "secret-marker"));
		reject(amount("paid_fee", "secret-marker"));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void boundsIgnoredTokensAtRootFillAndNestedLevels(int location) {
		for (String value : List.of("\"" + "x".repeat(256) + "\"",
				"\"" + "\\u0078".repeat(256) + "\"", "\"" + "😀".repeat(128) + "\"",
				"1".repeat(128), "-" + "1".repeat(127),
				"0." + "0".repeat(125) + "1", "1." + "0".repeat(122) + "e+00")) {
			assertThat(mapper.map(ignored(location, "ignored", value)).trades()).hasSize(1);
		}
		for (String value : List.of("\"" + "x".repeat(257) + "\"",
				"\"" + "\\u0078".repeat(257) + "\"", "\"" + "😀".repeat(128) + "x\"",
				"1".repeat(129), "-" + "1".repeat(128),
				"0." + "0".repeat(126) + "1", "1." + "0".repeat(123) + "e+00")) {
			reject(ignored(location, "ignored", value));
		}
		for (String name : List.of("x".repeat(256), "\\u0078".repeat(256), "😀".repeat(128))) {
			assertThat(mapper.map(ignored(location, name, "null")).trades()).hasSize(1);
		}
		for (String name : List.of("x".repeat(257), "\\u0078".repeat(257), "😀".repeat(128) + "x")) {
			reject(ignored(location, name, "null"));
		}
	}

	@Test
	void boundsSourceUtf16LengthBeforeParsing() {
		for (String json : List.of(order("done", fill("first")), ignored(0, "ignored", "\"😀\""))) {
			String boundary = json + " ".repeat(1_048_576 - json.length());
			assertThat(mapper.map(boundary).trades()).hasSize(1);
			reject(boundary + " ");
		}
	}

	@Test
	void boundsDepthIncludingRootAndRejectsVeryDeepInputBeforeTreeCreation() {
		for (int depth : List.of(64, 65, 10_000)) {
			for (boolean objects : List.of(false, true)) {
				String value = (objects ? "{\"n\":" : "[").repeat(depth - 1)
						+ "0" + (objects ? "}" : "]").repeat(depth - 1);
				String json = ignored(0, "ignored", value);
				if (depth == 64) {
					assertThat(mapper.map(json).trades()).hasSize(1);
				}
				else {
					reject(json);
				}
			}
		}
	}

	@Test
	void rejectsLaterIgnoredViolationsAndNestedDuplicateKeysWithoutPartialResults() {
		reject(order("done", fill("first") + "," + appendField(fill("second"), "ignored",
				"\"" + "x".repeat(257) + "\"")));
		reject(ignored(0, "ignored", "{\"n\":1,\"n\":2}"));
		// Only the root trades array has the execution count policy.
		assertThat(mapper.map(ignored(0, "ignored", "{\"trades\":[" + "0,".repeat(1000) + "0]}"))
				.trades()).hasSize(1);
	}

	private static String ignored(int location, String name, String value) {
		String trade = fill("first");
		if (location == 1) {
			return order("done", appendField(trade, name, value));
		}
		if (location == 2) {
			return appendField(order("done", trade), "ignored", "[{\"nested\":{\"" + name + "\":" + value + "}}]");
		}
		return appendField(order("done", trade), name, value);
	}

	private static String appendField(String json, String name, String value) {
		int end = json.lastIndexOf('}');
		return json.substring(0, end) + ",\"" + name + "\":" + value + json.substring(end);
	}

	private void reject(String json) {
		assertThatThrownBy(() -> mapper.map(json)).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).startsWith("Invalid Upbit order field:").doesNotContain("secret-marker");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}

	private static String amount(String field, String value) {
		if (List.of("executed_volume", "paid_fee").contains(field)) {
			return withText(order("done", fill("fill-1")), field, value);
		}
		return order("done", withText(fill("fill-1"), field, value));
	}

	private static String withText(String json, String field, String value) {
		return withRaw(json, field, "\"" + value + "\"");
	}

	private static String withRaw(String json, String field, String value) {
		return json.replaceFirst(Pattern.quote("\"" + field + "\":") + "\"[^\"]*\"",
				Matcher.quoteReplacement("\"" + field + "\":" + value));
	}

	private static String order(String state, String trades) {
		return """
				{"uuid":"order-1","market":"KRW-BTC","side":"bid","ord_type":"limit","state":"%s",
				"created_at":"2026-07-01T10:00:00+09:00","executed_volume":"0.0000000000000000000200",
				"paid_fee":"0.0000000000000000000100","price":"999999999","trades":[%s]}
				""".formatted(state, trades);
	}

	private static String fill(String id) {
		return """
				{"uuid":"%s","market":"KRW-BTC","side":"bid","price":"123.456789012345678901234567890",
				"volume":"0.0000000000000000000100","funds":"0.0000000000000000012345678900",
				"created_at":"2026-07-01T01:00:00Z"}
				""".formatted(id);
	}
}
