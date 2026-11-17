package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lmj.tradeledger.application.model.TossTransactionSnapshot;
import io.github.lmj.tradeledger.application.model.TossTransactionsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TossTransactionsMapperTest {

	private static final String MARKER = "fake-private-response-marker";
	private static final List<String> NULLABLE = List.of("symbol", "assetClass", "isinCode", "quantity",
			"filledPrice", "amount", "netAmount", "exchangeRate", "transactionTime");
	private static final List<String> REQUIRED_TEXT = List.of("transactionType", "marketCountry", "currency",
			"commission", "tax", "settlementDate");
	private static final List<String> DECIMALS = List.of("quantity", "filledPrice", "amount", "commission",
			"tax", "netAmount", "exchangeRate");
	private final TossTransactionsMapper mapper = new TossTransactionsMapper();

	@Test
	void mapsEveryFieldOfSettledDomesticRowWithoutRecalculatingCostsOrNetAmount() {
		var result = map(page(row(), "null", false)).transactions().getFirst();
		assertThat(result.transactionType()).isEqualTo("SELL");
		assertThat(result.marketCountry()).isEqualTo("KR");
		assertThat(result.currency()).isEqualTo("KRW");
		assertThat(result.symbol()).isEqualTo("005930");
		assertThat(result.assetClass()).isEqualTo("STOCK");
		assertThat(result.isinCode()).isEqualTo("KR7005930003");
		assertThat(result.quantity()).isEqualTo("10.00");
		assertThat(result.filledPrice()).isEqualTo("71000.0000");
		assertThat(result.amount()).isEqualTo("710000.00");
		assertThat(result.commission()).isEqualTo("1420.00");
		assertThat(result.tax()).isEqualTo("1420.00");
		// Deliberately independent of amount - commission - tax.
		assertThat(result.netAmount()).isEqualTo("707159.9900");
		assertThat(result.exchangeRate()).isNull();
		assertThat(result.settlementDate()).isEqualTo(LocalDate.of(2026, 9, 30));
		assertThat(result.settled()).isTrue();
		assertThat(result.transactionTime()).isEqualTo(Instant.parse("2026-09-29T21:30:00.123456789Z"));
		assertThat(result.isCancellation()).isFalse();
	}

	@Test
	void preservesSyntheticDomesticUnsettledUsStockOptionBondAndCashSemantics() {
		String unsettled = raw(raw(raw(text(row(), "settlementDate", "2000-01-01"), "filledPrice", "null"),
				"transactionTime", "null"), "settled", "false");
		String stock = text(text(text(text(row(), "marketCountry", "US"), "currency", "USD"),
				"symbol", "AAPL"), "isinCode", "US0378331005");
		stock = text(text(text(text(text(text(text(stock, "transactionType", "BUY"), "quantity", "3.00"),
				"filledPrice", "189.1200"), "amount", "567.3600"), "commission", "0.5700"),
				"tax", "0"), "netAmount", "567.9300");
		stock = text(text(stock, "exchangeRate", "1386.00"), "transactionTime", "2026-07-01T09:30:00-04:00");
		String usUnsettled = raw(raw(raw(raw(stock, "settled", "false"), "filledPrice", "null"),
				"exchangeRate", "null"), "transactionTime", "null");
		String option = raw(text(text(text(text(text(stock, "symbol", "AAPL261218C00150000"),
				"assetClass", "OPTION"), "quantity", "2"), "filledPrice", "1.3500"), "amount", "270.00"),
				"isinCode", "null");
		option = text(text(option, "commission", "0.2700"), "netAmount", "270.2700");
		String bond = raw(text(text(text(text(stock, "assetClass", "BOND"), "isinCode", "US91282CZZ99"),
				"quantity", "2"), "filledPrice", "99.2500"), "symbol", "null");
		bond = text(text(text(bond, "amount", "198.5000"), "commission", "0.2000"), "netAmount", "198.7000");
		var results = map(page(String.join(",", unsettled, row(), stock, usUnsettled, option, bond),
				"\" =+/%한😀 \"", true)).transactions();
		assertThat(results).hasSize(6);
		assertThat(results.get(0).filledPrice()).isNull();
		assertThat(results.get(0).amount()).isEqualTo("710000.00");
		assertThat(results.get(0).transactionTime()).isNull();
		assertThat(results.get(0).settled()).isFalse();
		assertThat(results.get(1).settled()).isTrue();
		assertThat(results.get(2).currency()).isEqualTo("USD");
		assertThat(results.get(2).exchangeRate()).isEqualTo("1386.00");
		assertThat(results.get(2).transactionTime()).isEqualTo(Instant.parse("2026-07-01T13:30:00Z"));
		assertThat(results.get(3).filledPrice()).isNull();
		assertThat(results.get(3).exchangeRate()).isNull();
		assertThat(results.get(4).assetClass()).isEqualTo("OPTION");
		assertThat(results.get(4).symbol()).isEqualTo("AAPL261218C00150000");
		assertThat(results.get(4).isinCode()).isNull();
		assertThat(results.get(4).amount()).isEqualTo("270.00");
		assertThat(results.get(5).assetClass()).isEqualTo("BOND");
		assertThat(results.get(5).symbol()).isNull();
		assertThat(results.get(5).isinCode()).isEqualTo("US91282CZZ99");

		String cash = row();
		for (String field : List.of("symbol", "assetClass", "isinCode", "quantity", "filledPrice", "exchangeRate")) {
			cash = raw(cash, field, "null");
		}
		for (String type : List.of("DEPOSIT", "WITHDRAWAL", "EXCHANGE_IN", "EXCHANGE_OUT", "DIVIDEND",
				"INTEREST", "FEE", "SECURITIES_TRANSFER_IN", "SECURITIES_TRANSFER_OUT")) {
			String source = text(cash, "transactionType", type);
			if (type.equals("DIVIDEND")) {
				source = text(text(text(source, "assetClass", "STOCK"), "symbol", "005930"),
						"isinCode", "KR7005930003");
			}
			if (type.startsWith("SECURITIES_")) {
				source = text(text(source, "symbol", "005930"), "isinCode", "KR7005930003");
				source = raw(raw(text(text(source, "assetClass", "STOCK"), "quantity", "2.00"),
						"amount", "null"), "netAmount", "null");
			}
			if (type.equals("EXCHANGE_IN")) {
				source = text(text(text(source, "marketCountry", "US"), "currency", "USD"), "exchangeRate", "1386.00");
			}
			for (boolean cancellation : type.equals("EXCHANGE_IN") ? List.of(false) : List.of(false, true)) {
				String fixture = raw(source, "isCancellation", String.valueOf(cancellation));
				var actual = map(page(fixture, "null", false)).transactions().getFirst();
				assertThat(actual.transactionType()).isEqualTo(type);
				assertThat(actual.isCancellation()).isEqualTo(cancellation);
				assertThat(actual.commission()).isEqualTo("1420.00");
				assertThat(actual.tax()).isEqualTo("1420.00");
				assertThat(actual.currency()).isEqualTo(type.equals("EXCHANGE_IN") ? "USD" : "KRW");
				assertThat(actual.netAmount()).isEqualTo(type.startsWith("SECURITIES_") ? null : "707159.9900");
			}
		}
	}

	@Test
	void requiresEveryKeyAndRetainsEachOfNineExplicitNulls() {
		for (String field : NULLABLE) {
			rejectLast(omit(row(), field));
			assertThat(value(map(page(raw(row(), field, "null"), "null", false)).transactions().getFirst(), field))
					.isNull();
			for (String value : List.of("42", "true", "[]", "{}", "\"\"", "\"   \"", "\"\\t\"")) {
				rejectLast(raw(row(), field, value));
			}
		}
		for (String field : REQUIRED_TEXT) {
			rejectLast(omit(row(), field));
			for (String value : List.of("null", "42", "true", "[]", "{}", "\"\"", "\"   \"", "\"\\t\"")) {
				rejectLast(raw(row(), field, value));
			}
		}
		for (String field : List.of("settled", "isCancellation")) {
			rejectLast(omit(row(), field));
			for (String value : List.of("null", "0", "1", "\"false\"", "[]", "{}")) {
				rejectLast(raw(row(), field, value));
			}
		}
		String nullableRow = row();
		for (String field : NULLABLE) {
			nullableRow = raw(nullableRow, field, "null");
		}
		assertThat(map(page(nullableRow, "null", false)).transactions()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"transactionType", "marketCountry", "currency", "assetClass"})
	void preservesUnknownCodesAsBoundedUnmodifiedUnicodeStrings(String field) {
		for (String value : List.of(" Future_Code 한😀 ", "x".repeat(256), "😀".repeat(128))) {
			assertThat(value(map(page(text(row(), field, value), "null", false)).transactions().getFirst(), field))
					.isEqualTo(value);
		}
		for (String raw : List.of("\"x\\u007f\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"")) {
			rejectLast(raw(row(), field, raw));
		}
		rejectLast(text(row(), field, "x".repeat(257)));
	}

	@Test
	void preservesEveryDecimalExactlyAndRejectsSignsExponentsNumericTokensAndOversize() {
		for (String field : DECIMALS) {
			for (String decimal : List.of("0", "000.0100", "1".repeat(30), "0." + "1".repeat(26) + "00")) {
				assertThat(value(map(page(text(row(), field, decimal), "null", false)).transactions().getFirst(), field))
						.isEqualTo(decimal);
			}
			for (String decimal : List.of("1".repeat(31), "0." + "1".repeat(29), "+1", "-0", "-1.0", "1e2",
					"1E-2", "NaN", "Infinity", "-Infinity", ".5", "1.", " 1", "1 ", "1,000", MARKER)) {
				rejectLast(text(row(), field, decimal));
			}
			for (String token : List.of("1", "1.25", "1e2", "NaN", "Infinity")) {
				rejectLast(raw(row(), field, token));
			}
		}
	}

	@Test
	void rejectsInvalidDatesAndOffsetlessTimesAndDoesNotInferSettled() {
		for (String date : List.of("2026-02-29", "2024-02-30", "2026-04-31", "2026-13-01", "2026-3-01",
				"+2026-03-01", "2026-03-01T00:00:00Z", MARKER)) {
			rejectLast(text(row(), "settlementDate", date));
		}
		for (String time : List.of("2026-03-01T09:30:00", "2026-02-30T00:00:00Z", "2026-01-01",
				"2026-03-01T09:30:00+25:00", MARKER)) {
			rejectLast(text(row(), "transactionTime", time));
		}
		assertThat(map(page(text(row(), "settlementDate", "2024-02-29"), "null", false))
				.transactions().getFirst().settlementDate()).isEqualTo(LocalDate.of(2024, 2, 29));
		assertThat(map(page(text(row(), "settlementDate", "2099-12-31"), "null", false))
				.transactions().getFirst().settled()).isTrue();
		assertThat(map(page(raw(text(row(), "settlementDate", "2000-01-01"), "settled", "false"), "null", false))
				.transactions().getFirst().settled()).isFalse();
	}

	@Test
	void requiresConsistentMetadataWithoutUsingPageSizeToDetermineContinuation() {
		assertThat(map(page("", "null", false)).transactions()).isEmpty();
		assertThat(map(page("", "\"cursor\"", true)).hasNext()).isTrue();
		assertThat(map(page(row(), "\"cursor\"", true)).hasNext()).isTrue();
		for (String cursor : List.of("\" =+/%한😀 \"", "\"%2F\"", "\"" + "x".repeat(256) + "\"",
				"\"" + "😀".repeat(128) + "\"", "\"\\u002b\\u002f\\u003d\"")) {
			assertThat(map(page("", cursor, true)).hasNext()).isTrue();
			reject(page("", cursor, false));
		}
		assertThat(map(page("", "\"\\u002b\\u002f\\u003d\"", true)).nextCursor()).isEqualTo("+/=");
		assertThat(map(page("", "\" =+/%한😀 \"", true)).nextCursor()).isEqualTo(" =+/%한😀 ");
		for (String cursor : List.of("null", "42", "true", "[]", "{}", "\"\"", "\"   \"", "\"\\t\"",
				"\"\\u2003\"", "\"x\\u007f\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"",
				"\"" + "x".repeat(257) + "\"", "\"" + "😀".repeat(128) + "x\"")) {
			reject(page("", cursor, true));
			if (!cursor.equals("null")) {
				reject(page("", cursor, false));
			}
		}
		String valid = page(row(), "null", false);
		reject(valid.replace("\"transactions\":[" + row() + "],", ""));
		reject(omit(valid, "nextCursor"));
		reject(omit(valid, "hasNext"));
		for (String raw : List.of("null", "42", "\"true\"", "[]", "{}")) {
			reject(raw(valid, "hasNext", raw));
		}
		for (String raw : List.of("null", "42", "true", "{}", "\"rows\"")) {
			reject("{\"result\":{\"transactions\":" + raw + ",\"nextCursor\":null,\"hasNext\":false}}");
		}
	}

	@Test
	void retainsOrderEqualRowsAndOneHundredItemsAndRejectsExcessWithoutTruncating() {
		String different = text(row(), "transactionType", "BUY");
		var page = map(page(String.join(",", row(), different, row()), "null", false));
		assertThat(page.transactions()).extracting(TossTransactionSnapshot::transactionType)
				.containsExactly("SELL", "BUY", "SELL");
		assertThat(page.transactions().get(0)).isEqualTo(page.transactions().get(2));
		assertThatThrownBy(() -> page.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
		for (int limit : List.of(1, 20, 100)) {
			var request = new TossTransactionsRequest(null, null, null, limit);
			String rows = String.join(",", Collections.nCopies(limit, row()));
			assertThat(mapper.map(request, page(rows, "null", false)).transactions()).hasSize(limit);
			assertThatThrownBy(() -> mapper.map(request, page(rows + "," + row(), "null", false)))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Toss transactions response").hasNoCause();
		}
		for (String row : List.of("null", "[]", "42", "true", "\"row\"", "{}")) {
			rejectLast(row);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3})
	void boundsIgnoredDecodedValuesNamesAndRawNumbersAtEveryLocation(int location) {
		for (String raw : List.of("\"" + "x".repeat(256) + "\"", "\"" + "\\u0078".repeat(256) + "\"",
				"\"" + "😀".repeat(128) + "\"", "1".repeat(128), "-" + "1".repeat(127),
				"0." + "0".repeat(125) + "1", "1." + "0".repeat(122) + "e+00")) {
			assertThat(map(ignored(location, "ignored", raw))).isNotNull();
		}
		for (String raw : List.of("\"" + "x".repeat(257) + "\"", "\"" + "\\u0078".repeat(257) + "\"",
				"\"" + "😀".repeat(128) + "x\"", "1".repeat(129), "-" + "1".repeat(128),
				"0." + "0".repeat(126) + "1", "1." + "0".repeat(123) + "e+00")) {
			reject(ignored(location, "ignored", raw));
		}
		for (String name : List.of("x".repeat(256), "\\u0078".repeat(256), "😀".repeat(128))) {
			assertThat(map(ignored(location, name, "null"))).isNotNull();
		}
		for (String name : List.of("x".repeat(257), "\\u0078".repeat(257), "😀".repeat(128) + "x")) {
			reject(ignored(location, name, "null"));
		}
	}

	@Test
	void boundsDocumentUtf16AndRootInclusiveDepthAndCountsOnlyDirectTransactions() {
		String json = page(row(), "null", false);
		String boundary = json + " ".repeat(1_048_576 - json.length());
		assertThat(map(boundary)).isNotNull();
		reject(boundary + " ");
		for (int depth : List.of(64, 65, 10_000)) {
			for (boolean objects : List.of(false, true)) {
				String raw = (objects ? "{\"n\":" : "[").repeat(depth - 1) + "0"
						+ (objects ? "}" : "]").repeat(depth - 1);
				if (depth == 64) {
					assertThat(map(ignored(0, "ignored", raw))).isNotNull();
				}
				else {
					reject(ignored(0, "ignored", raw));
				}
			}
		}
		String unrelated = "[" + "null,".repeat(100) + "null]";
		for (int location : List.of(0, 2, 3)) {
			assertThat(map(ignored(location, "transactions", unrelated))).isNotNull();
		}
		assertThat(map(ignored(0, "ignored", "{\"result\":{\"transactions\":" + unrelated + "}}"))).isNotNull();
		// Container and scalar elements must all count as page elements in the preflight.
		for (String item : List.of(row(), "null", "[]", "0", "true", "\"x\"")) {
			reject(page(String.join(",", Collections.nCopies(101, item)), "null", false));
		}
	}

	@Test
	void rejectsErrorsDuplicateKeysTrailingDocumentsAndDamagedLastRowsWithoutLeaks(CapturedOutput output) {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		var logs = new ListAppender<ILoggingEvent>();
		logs.start();
		root.addAppender(logs);
		try {
			String json = page(row(), "null", false);
			for (String invalid : List.of("", "null", "[]", "42", "{}", "{", "{\"" + MARKER + "\":}",
					json + " {}", json + " null", json + " " + MARKER, json.substring(0, json.length() - 1),
					"{\"error\":{\"message\":\"" + MARKER + "\"}}", append(json, "error", "null"),
					append(json, "error", "{\"message\":\"" + MARKER + "\"}"), append(json, "result", "{}"),
					json.replace("\"hasNext\":false", "\"hasNext\":false,\"hasNext\":false"),
					page(row() + ",{" + "\"" + MARKER + "\":", "null", false))) {
				reject(invalid);
			}
			for (String result : List.of("null", "[]", "42", "true", "\"text\"")) {
				reject("{\"result\":" + result + "}");
			}
			for (int location : List.of(0, 1, 2, 3)) {
				reject(ignored(location, MARKER, "{\"a\":1,\"\\u0061\":2}"));
				reject(ignored(location, MARKER, "{\"a\":1,\"a\":2}"));
			}
			rejectLast(row().replace("\"tax\":", "\"tax\":\"0\",\"tax\":"));
			rejectLast(text(row(), "transactionTime", MARKER));
			rejectLast(text(row(), "commission", MARKER));
			reject(null);
			assertThatThrownBy(() -> mapper.map(null, json)).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Invalid Toss transactions response").hasNoCause();
			assertThat(logs.list).isEmpty();
			assertThat(output.getAll()).doesNotContain(MARKER, "JsonParseException", "StreamReadException",
					"Unexpected character", "line: 1", "column:");
		}
		finally {
			root.detachAppender(logs);
			logs.stop();
		}
	}

	private TossTransactionsSnapshot map(String json) {
		return mapper.map(new TossTransactionsRequest(), json);
	}

	private void rejectLast(String last) {
		reject(page(row() + "," + last, "null", false));
	}

	private void reject(String json) {
		assertThatThrownBy(() -> map(json)).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss transactions response");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			var stack = new StringWriter();
			exception.printStackTrace(new PrintWriter(stack));
			assertThat(stack.toString()).doesNotContain(MARKER, "tools.jackson", "Unexpected character",
					"DateTimeParseException", "line: 1", "column:");
		});
	}

	private static String row() {
		return "{\"transactionType\":\"SELL\",\"marketCountry\":\"KR\",\"currency\":\"KRW\","
				+ "\"symbol\":\"005930\",\"assetClass\":\"STOCK\",\"isinCode\":\"KR7005930003\","
				+ "\"quantity\":\"10.00\",\"filledPrice\":\"71000.0000\",\"amount\":\"710000.00\","
				+ "\"commission\":\"1420.00\",\"tax\":\"1420.00\",\"netAmount\":\"707159.9900\",\"exchangeRate\":null,"
				+ "\"settlementDate\":\"2026-09-30\",\"settled\":true,"
				+ "\"transactionTime\":\"2026-09-30T06:30:00.123456789+09:00\",\"isCancellation\":false}";
	}

	private static String page(String rows, String cursor, boolean hasNext) {
		return "{\"result\":{\"transactions\":[" + rows + "],\"nextCursor\":" + cursor + ",\"hasNext\":" + hasNext + "}}";
	}

	private static String text(String json, String field, String value) {
		return raw(json, field, "\"" + value + "\"");
	}

	private static String raw(String json, String field, String value) {
		return field(field).matcher(json).replaceFirst(Matcher.quoteReplacement("\"" + field + "\":" + value));
	}

	private static String omit(String json, String field) {
		return field(field).matcher(json).replaceFirst("").replace("{,", "{").replace(",,", ",").replace(",}", "}");
	}

	private static Pattern field(String field) {
		return Pattern.compile("\"" + Pattern.quote(field) + "\":(?:\"(?:[^\"\\\\]|\\\\.)*\"|null|true|false|[0-9]+(?:\\.[0-9]+)?)");
	}

	private static String append(String json, String field, String value) {
		return json.substring(0, json.length() - 1) + ",\"" + field + "\":" + value + "}";
	}

	private static String ignored(int location, String field, String value) {
		String json = page(row(), "null", false);
		return switch (location) {
			case 0 -> append(json, field, value);
			case 1 -> "{\"result\":" + append(json.substring(10, json.length() - 1), field, value) + "}";
			case 2 -> page(append(row(), field, value), "null", false);
			case 3 -> append(json, "ignored", "{\"" + field + "\":" + value + "}");
			default -> throw new AssertionError(location);
		};
	}

	private static Object value(TossTransactionSnapshot row, String field) {
		try {
			return TossTransactionSnapshot.class.getMethod(field).invoke(row);
		}
		catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
