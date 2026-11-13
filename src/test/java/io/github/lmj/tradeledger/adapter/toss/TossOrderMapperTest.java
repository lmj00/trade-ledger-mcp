package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lmj.tradeledger.application.model.TossClosedOrdersSnapshot;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TossOrderMapperTest {

	private static final String ID = "Synthetic-Order%2F+한😀";
	private static final String MARKER = "fake-secret-response-marker";
	private static final TossOrderRequest REQUEST = new TossOrderRequest(ID);
	private static final List<String> DECIMALS = List.of("price", "quantity", "orderAmount", "filledQuantity",
			"averageFilledPrice", "filledAmount", "commission", "tax");
	private static final List<String> NULLABLE_EXECUTION = List.of("averageFilledPrice", "filledAmount",
			"commission", "tax", "filledAt", "settlementDate");
	private final TossOrderMapper mapper = new TossOrderMapper();

	@Test
	void mapsDomesticFilledOrderWithSeparatePricesAmountsAndCosts() {
		var result = map(fixture());
		assertThat(result.orderId()).isEqualTo(ID);
		assertThat(result.symbol()).isEqualTo("005930");
		assertThat(result.side()).isEqualTo("BUY");
		assertThat(result.orderType()).isEqualTo("LIMIT");
		assertThat(result.timeInForce()).isEqualTo("DAY");
		assertThat(result.status()).isEqualTo("FILLED");
		assertThat(result.price()).isEqualTo("70000.00");
		assertThat(result.quantity()).isEqualTo("10.00");
		assertThat(result.orderAmount()).isEqualTo("900.00");
		assertThat(result.currency()).isEqualTo("KRW");
		assertThat(result.orderedAt()).isEqualTo(Instant.parse("2026-03-28T00:30:00.123456789Z"));
		assertThat(result.canceledAt()).isEqualTo(Instant.parse("2026-03-28T00:32:00Z"));
		assertThat(result.execution().filledQuantity()).isEqualTo("10.00");
		assertThat(result.execution().averageFilledPrice()).isEqualTo("69999.5000");
		assertThat(result.execution().filledAmount()).isEqualTo("699995.0000");
		assertThat(result.execution().commission()).isEqualTo("1400.00");
		assertThat(result.execution().tax()).isEqualTo("12.30");
		assertThat(result.execution().filledAt()).isEqualTo(Instant.parse("2026-03-28T00:31:15Z"));
		assertThat(result.execution().settlementDate()).isEqualTo(LocalDate.of(2026, 3, 30));
	}

	@Test
	void mapsUsPartialAndRejectedUnfilledSyntheticOrdersWithoutInventingFills() {
		String partial = fixture().replace("005930", "AAPL").replace("KRW", "USD")
				.replace("LIMIT", "MARKET").replace("FILLED", "PARTIAL_FILLED");
		partial = withRaw(withRaw(partial, "price", "null"), "orderAmount", "null");
		partial = withRaw(withText(withText(partial, "quantity", "5"), "filledQuantity", "3"),
				"settlementDate", "null");
		partial = withText(withText(partial, "averageFilledPrice", "185.2500"), "filledAmount", "555.7500");
		partial = withRaw(withText(partial, "orderedAt", "2026-07-01T09:30:00-04:00"), "canceledAt", "null");
		var result = map(partial);
		assertThat(result.status()).isEqualTo("PARTIAL_FILLED");
		assertThat(result.symbol()).isEqualTo("AAPL");
		assertThat(result.currency()).isEqualTo("USD");
		assertThat(result.price()).isNull();
		assertThat(result.orderAmount()).isNull();
		assertThat(result.execution().filledQuantity()).isEqualTo("3");
		assertThat(result.execution().averageFilledPrice()).isEqualTo("185.2500");
		assertThat(result.execution().filledAmount()).isEqualTo("555.7500");
		assertThat(result.execution().settlementDate()).isNull();
		assertThat(result.orderedAt()).isEqualTo(Instant.parse("2026-07-01T13:30:00Z"));
		assertThat(result.canceledAt()).isNull();

		String rejected = withText(withText(partial, "status", "REJECTED"), "filledQuantity", "0");
		for (String field : NULLABLE_EXECUTION) {
			rejected = withRaw(rejected, field, "null");
		}
		var unfilled = map(rejected).execution();
		assertThat(unfilled.filledQuantity()).isEqualTo("0");
		assertThat(unfilled.averageFilledPrice()).isNull();
		assertThat(unfilled.filledAmount()).isNull();
		assertThat(unfilled.commission()).isNull();
		assertThat(unfilled.tax()).isNull();
		assertThat(unfilled.filledAt()).isNull();
		assertThat(unfilled.settlementDate()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"CANCELED", "REPLACED", "REJECTED", "CANCEL_REJECTED", "REPLACE_REJECTED"})
	void preservesRemainingCumulativeFillsRegardlessOfStatus(String status) {
		var result = map(withText(fixture(), "status", status));
		assertThat(result.status()).isEqualTo(status);
		assertThat(result.execution().filledQuantity()).isEqualTo("10.00");
		assertThat(result.execution().commission()).isEqualTo("1400.00");
		assertThat(result.execution().tax()).isEqualTo("12.30");
	}

	@Test
	void requiresExactRequestIdIncludingCaseWhitespaceAndUndecodedContent() {
		for (String id : List.of("different", ID.toLowerCase(), ID + " ", ID.replace("%2F", "/"))) {
			reject(withText(fixture(), "orderId", id));
		}
		for (String id : List.of("0", " Order%2F+ ", "x".repeat(256), "😀".repeat(128))) {
			assertThat(mapper.map(new TossOrderRequest(id), withText(fixture(), "orderId", id)).orderId())
					.isEqualTo(id);
		}
		assertThat(mapper.map(new TossOrderRequest("Order/"),
				withRaw(fixture(), "orderId", "\"Order\\u002f\"")).orderId()).isEqualTo("Order/");
		assertThatThrownBy(() -> mapper.map(null, fixture()))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss order response").hasNoCause();
	}

	@ParameterizedTest
	@ValueSource(strings = {"orderId", "symbol", "side", "orderType", "timeInForce", "status", "quantity",
			"currency", "orderedAt", "filledQuantity"})
	void rejectsMissingNullBlankAndWrongTypesForRequiredStrings(String field) {
		reject(omit(fixture(), field));
		for (String value : List.of("null", "42", "true", "[]", "{}", "\"\"", "\"   \"", "\"\\t\"")) {
			reject(withRaw(fixture(), field, value));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"averageFilledPrice", "filledAmount", "commission", "tax", "filledAt", "settlementDate"})
	void requiresNullableExecutionKeysAndPreservesExplicitNull(String field) {
		reject(omit(fixture(), field));
		assertThat(map(withRaw(fixture(), field, "null"))).isNotNull();
		for (String value : List.of("42", "true", "[]", "{}", "\"\"", "\"   \"")) {
			reject(withRaw(fixture(), field, value));
		}
	}

	@Test
	void acceptsOmittedOrNullOptionalFieldsAndRejectsTheirWrongTypes() {
		String absent = fixture();
		String explicitNull = fixture();
		for (String field : List.of("price", "orderAmount", "canceledAt")) {
			absent = omit(absent, field);
			explicitNull = withRaw(explicitNull, field, "null");
			for (String value : List.of("42", "true", "[]", "{}", "\"\"")) {
				reject(withRaw(fixture(), field, value));
			}
		}
		assertThat(map(absent)).isEqualTo(map(explicitNull));
		assertThat(map(absent).price()).isNull();
		assertThat(map(absent).orderAmount()).isNull();
		assertThat(map(absent).canceledAt()).isNull();
	}

	@Test
	void rejectsMissingOrInvalidObjectsAndErrorEnvelopes() {
		for (String value : List.of("null", "[]", "42", "true", "\"text\"")) {
			reject("{\"result\":" + value + "}");
			reject(fixture().replace("\"execution\":{", "\"execution\":" + value + ",\"ignored\":{"));
		}
		reject(omit(fixture(), "result"));
		reject(omit(fixture(), "execution"));
		reject("{\"error\":{\"message\":\"" + MARKER + "\"}}");
		reject(append(fixture(), "error", "null"));
		reject(append(fixture(), "error", "{\"message\":\"" + MARKER + "\"}"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"orderType", "timeInForce", "status", "currency"})
	void preservesFutureCodesAsBoundedNonblankStrings(String field) {
		assertThat(map(withText(fixture(), field, " Future_Code "))).isNotNull();
		assertThat(map(withText(fixture(), field, "x".repeat(256)))).isNotNull();
		reject(withText(fixture(), field, "x".repeat(257)));
		reject(withRaw(fixture(), field, "\"future\\u007fcode\""));
		var result = map(withText(withText(withText(fixture(), "orderType", "future-type"),
				"timeInForce", "future-tif"), "status", "future-status"));
		assertThat(result.orderType()).isEqualTo("future-type");
		assertThat(result.timeInForce()).isEqualTo("future-tif");
		assertThat(result.status()).isEqualTo("future-status");
		assertThat(map(withText(fixture(), "currency", "FUTURE_CURRENCY")).currency())
				.isEqualTo("FUTURE_CURRENCY");
	}

	@Test
	void enforcesThirtyCharacterDecimalContractWithoutFloatingPointOrArithmeticAssumptions() {
		for (String field : DECIMALS) {
			for (String value : List.of("1".repeat(30), "0." + "1".repeat(26) + "00", "-1." + "2".repeat(27),
					"+0.0000", "-0.0100")) {
				assertThat(decimal(map(withText(fixture(), field, value)), field)).isEqualTo(value);
			}
			for (String value : List.of("1".repeat(31), "1." + "2".repeat(29), "1e2", "1E-2", "NaN", "Infinity",
					"-Infinity", ".5", "1.", " 1", "1 ", "1,000", MARKER)) {
				reject(withText(fixture(), field, value));
			}
			for (String raw : List.of("1.25", "1", "1e2", "NaN", "Infinity")) {
				reject(withRaw(fixture(), field, raw));
			}
		}
		for (String value : List.of("-12.3400", "0", "+99.00")) {
			var result = map(withText(withText(fixture(), "commission", value), "tax", value));
			assertThat(result.execution().commission()).isEqualTo(value);
			assertThat(result.execution().tax()).isEqualTo(value);
		}
	}

	@Test
	void rejectsOffsetlessOrInvalidTimesAndInvalidCalendarDates() {
		for (String field : List.of("orderedAt", "canceledAt", "filledAt")) {
			for (String value : List.of("2026-03-28T09:30:00", "2026-02-30T01:00:00Z", "2026-01-01",
					"2026-03-28T09:30:00+25:00", MARKER)) {
				reject(withText(fixture(), field, value));
			}
		}
		for (String date : List.of("2026-02-29", "2026-04-31", "2026-13-01", "2026-3-01", "+2026-03-01",
				"2026-03-30T00:00:00Z", MARKER)) {
			reject(withText(fixture(), "settlementDate", date));
		}
		assertThat(map(withText(fixture(), "settlementDate", "2024-02-29")).execution().settlementDate())
				.isEqualTo(LocalDate.of(2024, 2, 29));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void boundsEvenIgnoredDecodedStringsNamesAndRawNumberTokensBeforeTreeCreation(int location) {
		for (String value : List.of("\"" + "x".repeat(256) + "\"", "\"" + "\\u0078".repeat(256) + "\"",
				"\"" + "😀".repeat(128) + "\"", "1".repeat(128), "-" + "1".repeat(127),
				"0." + "0".repeat(125) + "1", "1." + "0".repeat(122) + "e+00")) {
			assertThat(map(ignored(location, "ignored", value))).isNotNull();
		}
		for (String value : List.of("\"" + "x".repeat(257) + "\"", "\"" + "\\u0078".repeat(257) + "\"",
				"\"" + "😀".repeat(128) + "x\"", "1".repeat(129), "-" + "1".repeat(128),
				"0." + "0".repeat(126) + "1", "1." + "0".repeat(123) + "e+00")) {
			reject(ignored(location, "ignored", value));
		}
		for (String name : List.of("x".repeat(256), "\\u0078".repeat(256), "😀".repeat(128))) {
			assertThat(map(ignored(location, name, "null"))).isNotNull();
		}
		for (String name : List.of("x".repeat(257), "\\u0078".repeat(257), "😀".repeat(128) + "x")) {
			reject(ignored(location, name, "null"));
		}
	}

	@Test
	void boundsTotalUtf16LengthAndDepthIncludingRoot() {
		String json = fixture();
		String boundary = json + " ".repeat(1_048_576 - json.length());
		assertThat(map(boundary)).isNotNull();
		reject(boundary + " ");
		for (int depth : List.of(64, 65, 10_000)) {
			for (boolean objects : List.of(false, true)) {
				String value = (objects ? "{\"n\":" : "[").repeat(depth - 1) + "0"
						+ (objects ? "}" : "]").repeat(depth - 1);
				String nested = ignored(0, "ignored", value);
				if (depth == 64) {
					assertThat(map(nested)).isNotNull();
				}
				else {
					reject(nested);
				}
			}
		}
	}

	@Test
	void rejectsDuplicateKeysTrailingDocumentsAndDamagedTailsWithoutLeakingDiagnostics(CapturedOutput output) {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		var logs = new ListAppender<ILoggingEvent>();
		logs.start();
		root.addAppender(logs);
		try {
			for (String json : List.of("", "null", "[]", "42", "{}", "{", "{\"" + MARKER + "\":}",
					fixture() + " {}", fixture() + " null", fixture() + " " + MARKER,
					fixture().substring(0, fixture().length() - 1),
					append(fixture(), MARKER, "{\"tail\":"),
					append(fixture(), "result", "{}"),
					ignored(0, "ignored", "{\"a\":1,\"\\u0061\":2}"),
					ignored(1, "ignored", "{\"a\":1,\"a\":2}"),
					ignored(2, "ignored", "{\"a\":1,\"a\":2}"),
					fixture().replace("\"filledQuantity\":", "\"tax\":null,\"filledQuantity\":"))) {
				reject(json);
			}
			reject(null);
			reject(withText(fixture(), "filledAt", MARKER));
			reject(withText(fixture(), "commission", MARKER));
			reject(withText(fixture(), "orderId", MARKER));
			assertThat(logs.list).isEmpty();
			assertThat(output.getAll()).doesNotContain(MARKER, ID, "JsonParseException", "StreamReadException",
					"Unexpected character", "line: 1", "column:");
		}
		finally {
			root.detachAppender(logs);
			logs.stop();
		}
	}

	private TossOrderSnapshot map(String json) {
		return mapper.map(REQUEST, json);
	}

	private void reject(String json) {
		assertThatThrownBy(() -> map(json)).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss order response");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			var stack = new StringWriter();
			exception.printStackTrace(new PrintWriter(stack));
			assertThat(stack.toString()).doesNotContain(MARKER, ID, "tools.jackson", "Unexpected character",
					"DateTimeParseException", "line: 1", "column:");
		});
	}

	@Test
	void mapsOneClosedPageUsingTheSameOrderContractForFiveSyntheticScenarios() {
		String domestic = fixture();
		String partial = withText(fixture().replace("005930", "aApL").replace("KRW", "USD"),
				"status", "PARTIAL_FILLED");
		partial = withText(withText(partial, "quantity", "5"), "filledQuantity", "3");
		partial = withText(partial, "orderedAt", "2026-07-01T09:30:00-04:00");
		String rejected = withText(withText(fixture(), "status", "REJECTED"), "filledQuantity", "0");
		for (String field : NULLABLE_EXECUTION) {
			rejected = withRaw(rejected, field, "null");
		}
		String canceled = withText(fixture(), "status", "CANCELED");
		String replaced = withText(fixture(), "status", "REPLACED");
		var fixtures = List.of(domestic, partial, rejected, canceled, replaced);
		var expected = IntStream.range(0, fixtures.size()).mapToObj(i -> {
			String id = "synthetic-" + i;
			return mapper.map(new TossOrderRequest(id), withText(fixtures.get(i), "orderId", id));
		}).toList();
		String orders = IntStream.range(0, fixtures.size())
				.mapToObj(i -> orderJson(withText(fixtures.get(i), "orderId", "synthetic-" + i)))
				.collect(Collectors.joining(","));
		var page = closed(pageJson(orders, "\" =+/%한😀 \"", true));
		assertThat(page.orders()).containsExactlyElementsOf(expected);
		assertThat(page.orders()).extracting(TossOrderSnapshot::status)
				.containsExactly("FILLED", "PARTIAL_FILLED", "REJECTED", "CANCELED", "REPLACED");
		assertThat(page.orders().get(1).symbol()).isEqualTo("aApL");
		assertThat(page.orders().get(1).orderedAt()).isEqualTo(Instant.parse("2026-07-01T13:30:00Z"));
		assertThat(page.orders().get(2).execution().filledAmount()).isNull();
		assertThat(page.orders().get(3).execution().commission()).isEqualTo("1400.00");
		assertThat(page.orders().get(4).execution().tax()).isEqualTo("12.30");
		assertThat(page.nextCursor()).isEqualTo(" =+/%한😀 ");
		assertThat(page.hasNext()).isTrue();
		assertThatThrownBy(() -> page.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void requiresPageMetadataWithoutInferringContinuationFromItemCount() {
		assertThat(closed(pageJson("", "null", false)).orders()).isEmpty();
		assertThat(closed(pageJson("", "\"cursor\"", true)).hasNext()).isTrue();
		assertThat(closed(pageJson(orderJson(fixture()), "null", false)).hasNext()).isFalse();
		for (String cursor : List.of("\"x\"", "\"=+/%한😀\"", "\"" + "x".repeat(256) + "\"",
				"\"" + "😀".repeat(128) + "\"", "\"\\u002b\\u002f\\u003d\"")) {
			assertThat(closed(pageJson(orderJson(fixture()), cursor, true)).hasNext()).isTrue();
			rejectClosed(pageJson("", cursor, false));
		}
		String page = pageJson(orderJson(fixture()), "null", false);
		for (String field : List.of("orders", "nextCursor", "hasNext")) {
			rejectClosed(omit(page, field));
		}
		for (String raw : List.of("null", "42", "\"true\"", "[]", "{}")) {
			rejectClosed(page.replace("\"hasNext\":false", "\"hasNext\":" + raw));
		}
		for (String raw : List.of("null", "42", "true", "{}", "\"orders\"")) {
			rejectClosed("{\"result\":{\"orders\":" + raw + ",\"nextCursor\":null,\"hasNext\":false}}");
		}
		for (String raw : List.of("null", "42", "true", "[]", "{}", "\"\"", "\"   \"", "\"\\t\"",
				"\"x\\u007f\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"",
				"\"" + "x".repeat(257) + "\"", "\"" + "😀".repeat(128) + "x\"")) {
			rejectClosed(pageJson("", raw, true));
			if (!raw.equals("null")) {
				rejectClosed(pageJson("", raw, false));
			}
		}
		assertThat(closed(pageJson("", "\"\\u002b\\u002f\\u003d\"", true)).nextCursor()).isEqualTo("+/=");
	}

	@Test
	void boundsPageCountWithoutTruncationAndRejectsExactDuplicateIdsAndNullItems() {
		for (int limit : List.of(1, 20, 100)) {
			var request = new TossClosedOrdersRequest(null, null, null, null, limit);
			String orders = IntStream.range(0, limit)
					.mapToObj(i -> orderJson(withText(fixture(), "orderId", "id-" + i)))
					.collect(Collectors.joining(","));
			assertThat(mapper.mapClosedOrders(request, pageJson(orders, "null", false)).orders()).hasSize(limit);
			String excessive = pageJson(orders + "," + orderJson(withText(fixture(), "orderId", "extra")), "null", false);
			assertThatThrownBy(() -> mapper.mapClosedOrders(request, excessive))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss order response").hasNoCause();
		}
		String order = orderJson(fixture());
		rejectClosed(pageJson(order + "," + order, "null", false));
		rejectClosed(pageJson(order + "," + orderJson(withRaw(fixture(), "orderId", "\"Synthetic-Order%2F+한\\ud83d\\ude00\"")),
				"null", false));
		for (String raw : List.of("null", "[]", "42", "true", "\"order\"", "{}")) {
			rejectClosed(pageJson(order + "," + raw, "null", false));
		}
		var distinct = closed(pageJson(orderJson(withText(fixture(), "orderId", "id")) + ","
				+ orderJson(withText(fixture(), "orderId", "ID")), "null", false));
		assertThat(distinct.orders()).extracting(TossOrderSnapshot::orderId).containsExactly("id", "ID");
		assertThat(closed(pageJson(orderJson(withText(fixture(), "status", "FUTURE_STATUS")), "null", false))
				.orders().getFirst().status()).isEqualTo("FUTURE_STATUS");
	}

	@Test
	void retainsAllFieldContractsAndFailsTheWholePageWhenTheLastOrderIsInvalid() {
		for (String field : List.of("orderId", "symbol", "side", "orderType", "timeInForce", "status",
				"quantity", "currency", "orderedAt", "filledQuantity")) {
			rejectLast(omit(fixture(), field));
			for (String raw : List.of("null", "42", "true", "[]", "\"\"", "\"   \"")) {
				rejectLast(withRaw(fixture(), field, raw));
			}
		}
		rejectLast(omit(fixture(), "execution"));
		for (String raw : List.of("null", "42", "true", "[]", "\"execution\"")) {
			rejectLast(fixture().replace("\"execution\":{", "\"execution\":" + raw + ",\"ignored\":{"));
		}
		for (String field : NULLABLE_EXECUTION) {
			rejectLast(omit(fixture(), field));
			assertThat(closed(pageJson(orderJson(withRaw(fixture(), field, "null")), "null", false)).orders())
					.hasSize(1);
			for (String raw : List.of("42", "true", "[]", "{}", "\"\"")) {
				rejectLast(withRaw(fixture(), field, raw));
			}
		}
		for (String field : List.of("price", "orderAmount", "canceledAt")) {
			assertThat(closed(pageJson(orderJson(omit(fixture(), field)), "null", false)))
					.isEqualTo(closed(pageJson(orderJson(withRaw(fixture(), field, "null")), "null", false)));
			for (String raw : List.of("42", "true", "[]", "{}", "\"\"")) {
				rejectLast(withRaw(fixture(), field, raw));
			}
		}
		for (String field : DECIMALS) {
			for (String value : List.of("1".repeat(30), "0." + "1".repeat(26) + "00", "-1." + "2".repeat(27),
					"+0.0000", "-0.0100")) {
				var result = closed(pageJson(orderJson(withText(fixture(), field, value)), "null", false));
				assertThat(decimal(result.orders().getFirst(), field)).isEqualTo(value);
			}
			for (String value : List.of("1".repeat(31), "1e2", "NaN", "Infinity", ".5", "1.", " 1", "1 ", "1,000")) {
				rejectLast(withText(fixture(), field, value));
			}
			for (String raw : List.of("1", "1.25", "1e2")) {
				rejectLast(withRaw(fixture(), field, raw));
			}
		}
		for (String field : List.of("orderedAt", "canceledAt", "filledAt")) {
			for (String value : List.of("2026-03-28T09:30:00", "2026-02-30T01:00:00Z", MARKER)) {
				rejectLast(withText(fixture(), field, value));
			}
		}
		for (String value : List.of("2026-02-29", "2026-3-01", "+2026-03-01", MARKER)) {
			rejectLast(withText(fixture(), "settlementDate", value));
		}
		assertThat(closed(pageJson(orderJson(withText(fixture(), "settlementDate", "2024-02-29")), "null", false))
				.orders().getFirst().execution().settlementDate()).isEqualTo(LocalDate.of(2024, 2, 29));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3})
	void boundsIgnoredPageFieldsBeforeTreeCreation(int location) {
		for (String raw : List.of("\"" + "x".repeat(256) + "\"", "\"" + "\\u0078".repeat(256) + "\"",
				"\"" + "😀".repeat(128) + "\"", "1".repeat(128), "-" + "1".repeat(127),
				"0." + "0".repeat(125) + "1", "1." + "0".repeat(122) + "e+00")) {
			assertThat(closed(pageIgnored(location, "ignored", raw))).isNotNull();
		}
		for (String raw : List.of("\"" + "x".repeat(257) + "\"", "\"" + "\\u0078".repeat(257) + "\"",
				"\"" + "😀".repeat(128) + "x\"", "1".repeat(129), "-" + "1".repeat(128),
				"0." + "0".repeat(126) + "1", "1." + "0".repeat(123) + "e+00")) {
			rejectClosed(pageIgnored(location, "ignored", raw));
		}
		for (String name : List.of("x".repeat(256), "\\u0078".repeat(256), "😀".repeat(128))) {
			assertThat(closed(pageIgnored(location, name, "null"))).isNotNull();
		}
		for (String name : List.of("x".repeat(257), "\\u0078".repeat(257), "😀".repeat(128) + "x")) {
			rejectClosed(pageIgnored(location, name, "null"));
		}
	}

	@Test
	void boundsPageUtf16AndRootInclusiveDepthAndCountsOnlyDirectResultOrders() {
		String page = pageJson(orderJson(fixture()), "null", false);
		String boundary = page + " ".repeat(1_048_576 - page.length());
		assertThat(closed(boundary).orders()).hasSize(1);
		rejectClosed(boundary + " ");
		for (int depth : List.of(64, 65, 10_000)) {
			for (boolean objects : List.of(false, true)) {
				String value = (objects ? "{\"n\":" : "[").repeat(depth - 1) + "0"
						+ (objects ? "}" : "]").repeat(depth - 1);
				if (depth == 64) {
					assertThat(closed(append(page, "ignored", value))).isNotNull();
				}
				else {
					rejectClosed(append(page, "ignored", value));
				}
			}
		}
		// Unrelated arrays are bounded by the document limits, not the page item count.
		String unrelated = "[" + "null,".repeat(100) + "null]";
		assertThat(closed(pageIgnored(0, "orders", unrelated))).isNotNull();
		assertThat(closed(pageIgnored(2, "orders", unrelated))).isNotNull();
		assertThat(closed(pageIgnored(3, "orders", unrelated))).isNotNull();
		assertThat(closed(pageIgnored(0, "ignored", "{\"result\":{\"orders\":" + unrelated + "}}"))).isNotNull();
	}

	@Test
	void rejectsDamagedPageEnvelopesDuplicatesAndTailsWithoutLeakingInput(CapturedOutput output) {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		var logs = new ListAppender<ILoggingEvent>();
		logs.start();
		root.addAppender(logs);
		try {
			String page = pageJson(orderJson(fixture()), "null", false);
			for (String json : List.of("", "null", "[]", "42", "{}", "{", "{\"" + MARKER + "\":}",
					"{\"result\":null}", "{\"result\":[]}", "{\"error\":{\"message\":\"" + MARKER + "\"}}",
					page + " {}", page + " null", page + " " + MARKER,
					page.substring(0, page.length() - 1), append(page, MARKER, "{\"tail\":"),
					append(page, "error", "null"), append(page, "result", "{}"),
					page.replace("\"hasNext\":false", "\"hasNext\":false,\"hasNext\":false"))) {
				rejectClosed(json);
			}
			for (int location = 0; location < 4; location++) {
				rejectClosed(pageIgnored(location, "ignored", "{\"a\":1,\"\\u0061\":2}"));
			}
			rejectLast(fixture().replace("\"filledQuantity\":", "\"tax\":null,\"filledQuantity\":"));
			rejectLast(withText(fixture(), "filledAt", MARKER));
			rejectLast(withText(fixture(), "commission", MARKER));
			rejectClosed(null);
			assertThatThrownBy(() -> mapper.mapClosedOrders(null, page))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss order response").hasNoCause();
			assertThat(logs.list).isEmpty();
			assertThat(output.getAll()).doesNotContain(MARKER, ID, "JsonParseException", "StreamReadException",
					"Unexpected character", "line: 1", "column:");
		}
		finally {
			root.detachAppender(logs);
			logs.stop();
		}
	}

	private TossClosedOrdersSnapshot closed(String json) {
		return mapper.mapClosedOrders(new TossClosedOrdersRequest(), json);
	}

	private void rejectClosed(String json) {
		assertThatThrownBy(() -> closed(json)).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss order response");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			var stack = new StringWriter();
			exception.printStackTrace(new PrintWriter(stack));
			assertThat(stack.toString()).doesNotContain(MARKER, ID, "tools.jackson", "Unexpected character",
					"DateTimeParseException", "line: 1", "column:");
		});
	}

	private void rejectLast(String envelope) {
		rejectClosed(pageJson(orderJson(withText(fixture(), "orderId", "valid-first")) + ","
				+ orderJson(envelope), "null", false));
	}

	private static String orderJson(String envelope) {
		return envelope.substring("{\"result\":".length(), envelope.length() - 1);
	}

	private static String pageJson(String orders, String cursor, boolean hasNext) {
		return "{\"result\":{\"orders\":[" + orders + "],\"nextCursor\":" + cursor
				+ ",\"hasNext\":" + hasNext + "}}";
	}

	private static String pageIgnored(int location, String name, String value) {
		String page = pageJson(orderJson(fixture()), "null", false);
		String field = "\"" + name + "\":" + value + ",";
		return switch (location) {
			case 0 -> append(page, name, value);
			case 1 -> page.replace("\"result\":{", "\"result\":{" + field);
			case 2 -> page.replace("\"orders\":[{", "\"orders\":[{" + field);
			case 3 -> page.replace("\"execution\":{", "\"execution\":{" + field);
			default -> throw new AssertionError(location);
		};
	}

	private static String decimal(TossOrderSnapshot snapshot, String field) {
		return switch (field) {
			case "price" -> snapshot.price();
			case "quantity" -> snapshot.quantity();
			case "orderAmount" -> snapshot.orderAmount();
			case "filledQuantity" -> snapshot.execution().filledQuantity();
			case "averageFilledPrice" -> snapshot.execution().averageFilledPrice();
			case "filledAmount" -> snapshot.execution().filledAmount();
			case "commission" -> snapshot.execution().commission();
			case "tax" -> snapshot.execution().tax();
			default -> throw new AssertionError(field);
		};
	}

	private static String withText(String json, String field, String value) {
		return withRaw(json, field, "\"" + value + "\"");
	}

	private static String withRaw(String json, String field, String value) {
		return json.replaceFirst(Pattern.quote("\"" + field + "\":") + "(?:\"[^\"]*\"|null)",
				Matcher.quoteReplacement("\"" + field + "\":" + value));
	}

	private static String omit(String json, String field) {
		return json.replaceFirst(Pattern.quote("\"" + field + "\":"),
				Matcher.quoteReplacement("\"omitted_" + field + "\":"));
	}

	private static String append(String json, String field, String value) {
		int end = json.lastIndexOf('}');
		return json.substring(0, end) + ",\"" + field + "\":" + value + json.substring(end);
	}

	private static String ignored(int location, String name, String value) {
		String field = "\"" + name + "\":" + value + ",";
		return switch (location) {
			case 0 -> append(fixture(), name, value);
			case 1 -> fixture().replace("\"result\":{", "\"result\":{" + field);
			case 2 -> fixture().replace("\"execution\":{", "\"execution\":{" + field);
			default -> throw new AssertionError(location);
		};
	}

	private static String fixture() {
		// Synthetic summary only. Values deliberately need not satisfy inferred arithmetic.
		return """
				{"result":{"orderId":"%s","symbol":"005930","side":"BUY","orderType":"LIMIT",
				"timeInForce":"DAY","status":"FILLED","price":"70000.00","quantity":"10.00",
				"orderAmount":"900.00","currency":"KRW","orderedAt":"2026-03-28T09:30:00.123456789+09:00",
				"canceledAt":"2026-03-28T09:32:00+09:00","execution":{"filledQuantity":"10.00",
				"averageFilledPrice":"69999.5000","filledAmount":"699995.0000","commission":"1400.00",
				"tax":"12.30","filledAt":"2026-03-28T09:31:15+09:00","settlementDate":"2026-03-30"}}}
				""".formatted(ID).stripTrailing();
	}
}
