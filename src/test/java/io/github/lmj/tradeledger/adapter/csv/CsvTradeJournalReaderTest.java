package io.github.lmj.tradeledger.adapter.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.PnlBreakdown;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
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
		assertThat(trades).extracting(trade -> trade.entryPrice().currency().value())
				.containsOnly("USDT");

		RealizedPnlCalculator calculator = new RealizedPnlCalculator();
		List<PnlBreakdown> pnl = trades.stream().map(calculator::calculate).toList();

		assertThat(pnl.getFirst().grossPnl().amount()).isEqualByComparingTo("246.8617");
		assertThat(pnl.getFirst().netPnl().amount()).isEqualByComparingTo("241.4917");
		assertThat(pnl.get(1).grossPnl().amount()).isEqualByComparingTo("175.4375");
		assertThat(pnl.get(1).netPnl().amount()).isEqualByComparingTo("171.3125");
		assertThat(pnl.getLast().grossPnl().amount()).isEqualByComparingTo("-55.00275");
		assertThat(pnl.getLast().netPnl().amount()).isEqualByComparingTo("-57.01775");

		BigDecimal totalGross = pnl.stream()
				.map(result -> result.grossPnl().amount())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal totalFees = pnl.stream()
				.map(result -> result.fees().amount())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal totalNet = pnl.stream()
				.map(result -> result.netPnl().amount())
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		assertThat(totalGross).isEqualByComparingTo("367.29645");
		assertThat(totalFees).isEqualByComparingTo("11.51");
		assertThat(totalNet).isEqualByComparingTo("355.78645");
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
}
