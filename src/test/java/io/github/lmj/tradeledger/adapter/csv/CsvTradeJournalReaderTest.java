package io.github.lmj.tradeledger.adapter.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvTradeJournalReaderTest {

	private static final String HEADER =
			"trade_id,symbol,market,direction,quantity,entry_price,exit_price,fees,"
					+ "currency,opened_at,closed_at,strategy";

	@TempDir
	Path journalRoot;

	@Test
	void readsSanitizedSampleJournal() throws IOException {
		Path sample = Path.of("src/test/resources/journals/sample-trades.csv");
		Files.copy(sample, journalRoot.resolve("sample-trades.csv"));

		List<Trade> trades = new CsvTradeJournalReader(journalRoot).read("sample-trades");

		assertThat(trades).hasSize(3);
		assertThat(trades.getFirst().tradeId()).isEqualTo("trade-001");
		assertThat(trades.get(1).direction()).isEqualTo(Direction.SHORT);
		assertThat(trades.getLast().quantity()).isEqualByComparingTo("0.055");
		assertThat(trades.getFirst().entryPrice().amount()).isEqualByComparingTo("60000.25");
		assertThat(trades.getFirst().fees().amount()).isEqualByComparingTo("5.37");
		assertThat(trades).extracting(trade -> trade.entryPrice().currency().value())
				.containsOnly("USDT");
	}

	@Test
	void acceptsHeadersInDifferentOrder() throws IOException {
		writeJournal("reordered", """
				strategy,trade_id,symbol,market,direction,quantity,entry_price,exit_price,fees,\
				currency,opened_at,closed_at
				breakout,trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z
				""");

		List<Trade> trades = new CsvTradeJournalReader(journalRoot).read("reordered");

		assertThat(trades).singleElement()
				.extracting(Trade::strategy)
				.isEqualTo("breakout");
	}

	@Test
	void reportsInvalidFieldWithPhysicalLineNumber() throws IOException {
		writeJournal("invalid-field", HEADER + "\n"
				+ validRow("trade-001", "USDT") + "\n"
				+ validRow("trade-002", "USDT").replace(",0.1,", ",not-a-number,") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("invalid-field"),
				CsvJournalErrorCode.INVALID_FIELD,
				3L,
				"quantity");
	}

	@Test
	void rejectsMissingAndUnknownHeaders() throws IOException {
		writeJournal("invalid-header", HEADER.replace("strategy", "memo") + "\n"
				+ validRow("trade-001", "USDT") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("invalid-header"),
				CsvJournalErrorCode.INVALID_HEADER,
				null,
				null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"quantity", "entry_price", "exit_price", "fees"})
	void rejectsLongDecimalBelowFileLimitWithoutExposingInput(String field) throws IOException {
		String value = "+1." + "7".repeat(20_000);
		String[] values = validRow("trade-002", "USDT").split(",");
		values[CsvTradeSchema.HEADERS.indexOf(field)] = value;
		writeJournal("long-decimal", HEADER + "\n" + validRow("trade-001", "USDT") + "\n"
				+ String.join(",", values) + "\n");
		assertThat(Files.size(journalRoot.resolve("long-decimal.csv")))
				.isLessThan(CsvTradeJournalReader.DEFAULT_MAX_FILE_SIZE_BYTES);

		assertThatThrownBy(() -> new CsvTradeJournalReader(journalRoot).read("long-decimal"))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(3);
					assertThat(exception.field()).hasValue(field);
					assertThat(exception).hasMessageContaining("128").hasMessageNotContaining(value);
					assertThat(exception.getCause()).isNull();
				});
	}

	@Test
	void rejectsDuplicateHeaders() throws IOException {
		writeJournal("duplicate-header", HEADER.replace("strategy", "symbol") + "\n"
				+ validRow("trade-001", "USDT") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("duplicate-header"),
				CsvJournalErrorCode.INVALID_HEADER,
				null,
				null);
	}

	@Test
	void rejectsRowsWithWrongColumnCount() throws IOException {
		writeJournal("short-row", HEADER + "\n"
				+ validRow("trade-001", "USDT").replace(",breakout", "") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("short-row"),
				CsvJournalErrorCode.INVALID_FIELD,
				2L,
				null);
	}

	@Test
	void rejectsEmptyJournal() throws IOException {
		writeJournal("empty", HEADER + "\n\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("empty"),
				CsvJournalErrorCode.EMPTY_JOURNAL,
				null,
				null);
	}

	@Test
	void rejectsDuplicateTradeId() throws IOException {
		writeJournal("duplicates", HEADER + "\n"
				+ validRow("trade-001", "USDT") + "\n"
				+ validRow("trade-001", "USDT") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("duplicates"),
				CsvJournalErrorCode.DUPLICATE_TRADE_ID,
				3L,
				"trade_id");
	}

	@Test
	void rejectsMixedCurrencies() throws IOException {
		writeJournal("mixed-currencies", HEADER + "\n"
				+ validRow("trade-001", "USDT") + "\n"
				+ validRow("trade-002", "USD") + "\n");

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("mixed-currencies"),
				CsvJournalErrorCode.MIXED_CURRENCY,
				3L,
				"currency");
	}

	@Test
	void rejectsJournalOverTradeLimit() throws IOException {
		writeJournal("too-many", HEADER + "\n"
				+ validRow("trade-001", "USDT") + "\n"
				+ validRow("trade-002", "USDT") + "\n");

		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, 1024 * 1024, 1);

		assertJournalError(
				() -> reader.read("too-many"),
				CsvJournalErrorCode.TOO_MANY_TRADES,
				3L,
				null);
	}

	@Test
	void rejectsJournalOverFileSizeLimit() throws IOException {
		writeJournal("too-large", HEADER + "\n" + validRow("trade-001", "USDT") + "\n");

		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, 10, 10);

		assertJournalError(
				() -> reader.read("too-large"),
				CsvJournalErrorCode.FILE_TOO_LARGE,
				null,
				null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"breakout", "돌파전략"})
	void acceptsJournalExactlyAtByteLimit(String strategy) throws IOException {
		String content = HEADER + "\n"
				+ validRow("trade-001", "USDT").replace("breakout", strategy) + "\n";
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		writeJournal("exact-limit", content);
		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, bytes.length, 10);
		TrackingInputStream input = new TrackingInputStream(bytes);

		assertThat(reader.read("exact-limit")).singleElement()
				.extracting(Trade::strategy).isEqualTo(strategy);
		assertThat(reader.read(input, "exact-limit")).singleElement()
				.extracting(Trade::strategy).isEqualTo(strategy);
		assertThat(input.closed).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"breakout", "돌파전략"})
	void rejectsStreamOneByteOverLimit(String strategy) {
		String content = HEADER + "\n"
				+ validRow("trade-001", "USDT").replace("breakout", strategy) + "\n";
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		TrackingInputStream input = new TrackingInputStream(bytes);
		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, bytes.length - 1, 10);

		assertJournalError(() -> reader.read(input, "growing"),
				CsvJournalErrorCode.FILE_TOO_LARGE, null, null);
		assertThat(input.closed).isTrue();
	}

	@Test
	void stopsReadingLargeSingleRecordAtByteLimit() {
		String content = HEADER + "\n" + validRow("trade-001", "USDT")
				.replace("breakout", "가".repeat(20_000)) + "\n";
		TrackingInputStream input = new TrackingInputStream(content.getBytes(StandardCharsets.UTF_8));
		int limit = 10_000;
		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, limit, 10);

		assertJournalError(() -> reader.read(input, "growing"),
				CsvJournalErrorCode.FILE_TOO_LARGE, null, null);
		assertThat(input.bytesRead).isEqualTo(limit + 1);
		assertThat(input.closed).isTrue();
	}

	@Test
	void closesStreamWhenLimitIsExceededWhileReadingHeader() {
		TrackingInputStream input = new TrackingInputStream(HEADER.getBytes(StandardCharsets.UTF_8));
		CsvTradeJournalReader reader = new CsvTradeJournalReader(journalRoot, 10, 10);

		assertJournalError(() -> reader.read(input, "growing"),
				CsvJournalErrorCode.FILE_TOO_LARGE, null, null);
		assertThat(input.bytesRead).isEqualTo(11);
		assertThat(input.closed).isTrue();
	}

	@Test
	void closesStreamOnInvalidHeader() {
		TrackingInputStream input = new TrackingInputStream("invalid\n".getBytes(StandardCharsets.UTF_8));

		assertJournalError(() -> new CsvTradeJournalReader(journalRoot).read(input, "invalid"),
				CsvJournalErrorCode.INVALID_HEADER, null, null);
		assertThat(input.closed).isTrue();
	}

	@Test
	void closesStreamOnReadFailure() {
		TrackingInputStream input = new TrackingInputStream(new byte[0]) {
			@Override
			public int read(byte[] bytes, int offset, int length) throws IOException {
				throw new IOException("read failed");
			}
		};

		assertJournalError(() -> new CsvTradeJournalReader(journalRoot).read(input, "unreadable"),
				CsvJournalErrorCode.JOURNAL_READ_FAILED, null, null);
		assertThat(input.closed).isTrue();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "../private", "/tmp/trades", "trades.csv", "with space"})
	void rejectsUnsafeJournalName(String journalName) {
		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read(journalName),
				CsvJournalErrorCode.INVALID_JOURNAL_NAME,
				null,
				null);
	}

	@Test
	void reportsMissingJournalWithoutExposingAbsolutePath() {
		assertThatThrownBy(() -> new CsvTradeJournalReader(journalRoot).read("missing"))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.JOURNAL_NOT_FOUND);
					assertThat(exception.getMessage()).doesNotContain(journalRoot.toString());
				});
	}

	@Test
	void rejectsSymbolicLinkThatEscapesJournalRoot(@TempDir Path outsideRoot) throws IOException {
		Path outsideFile = outsideRoot.resolve("outside.csv");
		Files.writeString(outsideFile, HEADER + "\n" + validRow("trade-001", "USDT") + "\n");
		Files.createSymbolicLink(journalRoot.resolve("linked.csv"), outsideFile);

		assertJournalError(
				() -> new CsvTradeJournalReader(journalRoot).read("linked"),
				CsvJournalErrorCode.PATH_OUTSIDE_ROOT,
				null,
				null);
	}

	private void writeJournal(String journalName, String content) throws IOException {
		Files.writeString(journalRoot.resolve(journalName + ".csv"), content);
	}

	private static String validRow(String tradeId, String currency) {
		return "%s,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,%s,"
				.formatted(tradeId, currency)
				+ "2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout";
	}

	private static void assertJournalError(
			ThrowingOperation operation,
			CsvJournalErrorCode expectedCode,
			Long expectedLine,
			String expectedField) {
		assertThatThrownBy(operation::run)
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(expectedCode);
					if (expectedLine == null) {
						assertThat(exception.lineNumber()).isEmpty();
					}
					else {
						assertThat(exception.lineNumber()).hasValue(expectedLine);
					}
					if (expectedField == null) {
						assertThat(exception.field()).isEmpty();
					}
					else {
						assertThat(exception.field()).hasValue(expectedField);
					}
				});
	}

	@FunctionalInterface
	private interface ThrowingOperation {

		void run() throws Exception;
	}

	private static class TrackingInputStream extends InputStream {

		private final ByteArrayInputStream input;
		private int bytesRead;
		private boolean closed;

		private TrackingInputStream(byte[] bytes) {
			input = new ByteArrayInputStream(bytes);
		}

		@Override
		public int read() {
			int value = input.read();
			if (value != -1) {
				bytesRead++;
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			int count = input.read(bytes, offset, length);
			if (count > 0) {
				bytesRead += count;
			}
			return count;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
