package io.github.lmj.tradeledger.adapter.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CsvTradeMapperTest {

	private static final String HEADER = String.join(",", CsvTradeSchema.HEADERS);

	private final CsvTradeMapper mapper = new CsvTradeMapper();

	@ParameterizedTest
	@ValueSource(strings = { "market", "direction", "opened_at", "closed_at" })
	void rejectsInvalidParsedFieldsWithoutRetainingInput(String field) throws IOException {
		String marker = "synthetic-csv-" + field + "-j8z1";
		assertThat(marker.length()).isLessThanOrEqualTo(256);
		CSVRecord record = record(rowWithField(field, marker));
		String description = parsingDescription(field);

		assertThatThrownBy(() -> mapper.map(record, 23))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(23);
					assertThat(exception.field()).hasValue(field);
					assertThat(exception).hasMessage(description);
					assertThat(exception.getMessage()).doesNotContain(marker, marker.toUpperCase(Locale.ROOT));
					// No retained parser means no parsedString or nested/suppressed input either.
					assertThat(exception.getCause()).isNull();
					assertThat(exception.getSuppressed()).isEmpty();
				});
	}

	@Test
	void rejectsInvalidSymbolWithoutExposingInputInCauseChain() throws IOException {
		String marker = "synthetic-symbol-d2t4";
		String raw = marker + "!";
		assertThat(raw.length()).isLessThanOrEqualTo(32);
		CSVRecord record = record("trade-001," + raw + ",CRYPTO,LONG,0.1,60000,62000,5,USDT,"
				+ "2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout");

		assertThatThrownBy(() -> mapper.map(record, 17))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(17);
					assertThat(exception.field()).hasValue("symbol");
					assertThat(exception).hasMessageContaining("invalid symbol");
					assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
					for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
						assertThat(cause.getMessage()).doesNotContain(marker, marker.toUpperCase(Locale.ROOT));
					}
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"quantity", "entry_price", "exit_price", "fees"})
	void maps128CharacterDecimalsExactlyAfterCsvTrimming(String field) throws IOException {
		for (String value : List.of("1".repeat(128), "+" + "1".repeat(127),
				"1." + "1".repeat(126), "+1." + "1".repeat(125))) {
			assertThat(value).hasSize(128);
			for (String csvValue : List.of(value, "  " + value + "  ", "\"  " + value + "  \"")) {
				CSVRecord record = recordWithDecimal(field, csvValue);
				assertThat(record.get(field)).isEqualTo(value);

				Trade trade = mapper.map(record, 2);

				BigDecimal actual = switch (field) {
					case "quantity" -> trade.quantity();
					case "entry_price" -> trade.entryPrice().amount();
					case "exit_price" -> trade.exitPrice().amount();
					case "fees" -> trade.fees().amount();
					default -> throw new IllegalArgumentException("unexpected decimal field");
				};
				assertThat(actual).isEqualByComparingTo(new BigDecimal(value));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"quantity", "entry_price", "exit_price", "fees"})
	void rejects129CharacterDecimalsIncludingSignAndDecimalPoint(String field) throws IOException {
		for (String value : List.of("1".repeat(129), "+" + "1".repeat(128),
				"1." + "1".repeat(127), "+1." + "1".repeat(126), "-1." + "1".repeat(126))) {
			assertThat(value).hasSize(129);
			CSVRecord record = recordWithDecimal(field, value);

			assertThatThrownBy(() -> mapper.map(record, 7))
					.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
						assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
						assertThat(exception.lineNumber()).hasValue(7);
						assertThat(exception.field()).hasValue(field);
						assertThat(exception).hasMessageContaining("128").hasMessageNotContaining(value);
						assertThat(exception.getCause()).isNull();
					});
		}
	}

	@Test
	void mapsCsvFieldsToTrade() throws IOException {
		CSVRecord record = record("""
				trade-001,btc-usdt,crypto,long,0.1,60000,62000,5,usdt,\
				2026-07-01T10:00:00+09:00,2026-07-01T12:00:00+09:00,breakout
				""");

		Trade trade = mapper.map(record, 2);

		assertThat(trade.tradeId()).isEqualTo("trade-001");
		assertThat(trade.symbol().value()).isEqualTo("BTC-USDT");
		assertThat(trade.market()).isEqualTo(Market.CRYPTO);
		assertThat(trade.direction()).isEqualTo(Direction.LONG);
		assertThat(trade.quantity()).isEqualByComparingTo("0.1");
		assertThat(trade.entryPrice().amount()).isEqualByComparingTo("60000");
		assertThat(trade.exitPrice().amount()).isEqualByComparingTo("62000");
		assertThat(trade.fees().amount()).isEqualByComparingTo("5");
		assertThat(trade.entryPrice().currency().value()).isEqualTo("USDT");
		assertThat(trade.openedAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
		assertThat(trade.closedAt()).isEqualTo(Instant.parse("2026-07-01T03:00:00Z"));
		assertThat(trade.strategy()).isEqualTo("breakout");
	}

	@Test
	void preservesCommaInsideQuotedStrategy() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,"breakout, volume"
				""");

		Trade trade = mapper.map(record, 2);

		assertThat(trade.strategy()).isEqualTo("breakout, volume");
	}

	@Test
	void rejectsScientificNotationWithFieldAndLineNumber() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,1e-1,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("quantity");
				});
	}

	@Test
	void rejectsTimestampWithoutOffset() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,\
				2026-07-01T01:00:00,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("opened_at");
				});
	}

	@Test
	void translatesDomainValidationFailureToCsvField() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("quantity");
					assertThat(exception).hasMessageContaining("positive");
				});
	}

	@Nested
	@SpringBootTest(properties = "trade-ledger.journal.root=src/test/resources/journals")
	class McpBoundaryTests {

		@Autowired
		ApplicationContext applicationContext;

		@MockitoBean
		TradeJournalReader journalReader;

		@ParameterizedTest
		@ValueSource(strings = { "market", "direction", "opened_at", "closed_at" })
		void registeredSummaryRejectsInvalidCsvParsedFieldsWithoutExposingInput(String field) {
			String marker = "synthetic-summary-" + field + "-k9a2";
			assertThat(marker.length()).isLessThanOrEqualTo(256);
			String csv = HEADER + "\n" + rowWithField(field, marker) + "\n";
			CsvTradeJournalReader csvReader = new CsvTradeJournalReader(Path.of("."));
			// Replace only filesystem access; parsing still uses the real CSV reader and mapper.
			when(journalReader.read("synthetic-parsing")).thenAnswer(invocation -> csvReader.read(
					new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "synthetic-parsing"));

			CallToolResult result = callSummary("synthetic-parsing");

			assertAll("registered summarize_journal invalid " + field + " response",
					() -> assertThat(result.isError()).isTrue(),
					() -> assertThat(result.content()).isNotEmpty(),
					() -> assertThat(result.content().toString()).contains(parsingDescription(field))
							.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)),
					() -> assertThat(String.valueOf(result.structuredContent()))
							.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)));
		}

		@Test
		void registeredSummaryReturnsCsvSymbolErrorWithoutExposingInput() {
			String marker = "synthetic-symbol-g5w7";
			String raw = marker + "!";
			assertThat(raw.length()).isLessThanOrEqualTo(32);
			String csv = HEADER + "\ntrade-001," + raw + ",CRYPTO,LONG,0.1,60000,62000,5,USDT,"
					+ "2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout\n";
			CsvTradeJournalReader csvReader = new CsvTradeJournalReader(Path.of("."));
			// Replace filesystem access; retain the real CSV reader, mapper, and domain validation.
			when(journalReader.read("synthetic-symbol")).thenAnswer(invocation -> csvReader.read(
					new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "synthetic-symbol"));
			Object bean = applicationContext.getBean("toolSpecs");
			assertThat(bean).isInstanceOf(List.class);
			SyncToolSpecification specification = ((List<?>) bean).stream()
					.filter(SyncToolSpecification.class::isInstance)
					.map(SyncToolSpecification.class::cast)
					.filter(candidate -> candidate.tool().name().equals("summarize_journal"))
					.findFirst()
					.orElseThrow();

			CallToolResult result = specification.callHandler().apply(null,
					CallToolRequest.builder("summarize_journal")
							.arguments(Map.of("journalName", "synthetic-symbol"))
							.build());

			assertThat(result.isError()).isTrue();
			assertThat(result.content()).isNotEmpty();
			assertThat(result.content().toString()).contains("invalid symbol")
					.doesNotContain(marker, marker.toUpperCase(Locale.ROOT));
			assertThat(String.valueOf(result.structuredContent()))
					.doesNotContain(marker, marker.toUpperCase(Locale.ROOT));
		}

		private CallToolResult callSummary(String journalName) {
			Object bean = applicationContext.getBean("toolSpecs");
			assertThat(bean).isInstanceOf(List.class);
			SyncToolSpecification specification = ((List<?>) bean).stream()
					.filter(SyncToolSpecification.class::isInstance)
					.map(SyncToolSpecification.class::cast)
					.filter(candidate -> candidate.tool().name().equals("summarize_journal"))
					.findFirst()
					.orElseThrow();
			return specification.callHandler().apply(null,
					CallToolRequest.builder("summarize_journal")
							.arguments(Map.of("journalName", journalName))
							.build());
		}
	}

	private static String parsingDescription(String field) {
		return switch (field) {
			case "market", "direction" -> "unsupported " + field + " value";
			case "opened_at", "closed_at" -> "field must be an ISO-8601 timestamp with an offset";
			default -> throw new AssertionError(field);
		};
	}

	private static String rowWithField(String field, String value) {
		String[] values = ("trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,"
				+ "2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout").split(",");
		values[CsvTradeSchema.HEADERS.indexOf(field)] = value;
		return String.join(",", values);
	}

	private static CSVRecord recordWithDecimal(String field, String value) throws IOException {
		return record(rowWithField(field, value));
	}

	private static CSVRecord record(String row) throws IOException {
		String csv = HEADER + System.lineSeparator() + row;
		try (CSVParser parser = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setTrim(true)
				.get()
				.parse(new StringReader(csv))) {
			List<CSVRecord> records = parser.getRecords();
			assertThat(records).hasSize(1);
			return records.getFirst();
		}
	}
}
