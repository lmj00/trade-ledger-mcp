package io.github.lmj.tradeledger.adapter.csv;

import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.DUPLICATE_TRADE_ID;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.EMPTY_JOURNAL;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.FILE_TOO_LARGE;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.INVALID_FIELD;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.INVALID_FILE_TYPE;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.INVALID_HEADER;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.INVALID_JOURNAL_NAME;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.JOURNAL_NOT_FOUND;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.JOURNAL_READ_FAILED;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.MIXED_CURRENCY;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.PATH_OUTSIDE_ROOT;
import static io.github.lmj.tradeledger.adapter.csv.CsvJournalErrorCode.TOO_MANY_TRADES;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Reads a bounded UTF-8 CSV journal from an allowlisted root directory.
 */
public final class CsvTradeJournalReader {

	public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
	public static final int DEFAULT_MAX_TRADES = 10_000;

	private static final Pattern VALID_JOURNAL_NAME =
			Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

	private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
			.setHeader()
			.setSkipHeaderRecord(true)
			.setIgnoreEmptyLines(true)
			.setTrim(true)
			.setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
			.setAllowMissingColumnNames(false)
			.get();

	private final Path journalRoot;
	private final long maxFileSizeBytes;
	private final int maxTrades;
	private final CsvTradeMapper mapper;

	public CsvTradeJournalReader(Path journalRoot) {
		this(journalRoot, DEFAULT_MAX_FILE_SIZE_BYTES, DEFAULT_MAX_TRADES);
	}

	public CsvTradeJournalReader(Path journalRoot, long maxFileSizeBytes, int maxTrades) {
		this.journalRoot = Objects.requireNonNull(journalRoot, "journal root must not be null")
				.toAbsolutePath()
				.normalize();
		if (maxFileSizeBytes <= 0) {
			throw new IllegalArgumentException("maximum file size must be positive");
		}
		if (maxTrades <= 0) {
			throw new IllegalArgumentException("maximum trade count must be positive");
		}
		this.maxFileSizeBytes = maxFileSizeBytes;
		this.maxTrades = maxTrades;
		this.mapper = new CsvTradeMapper();
	}

	public List<Trade> read(String journalName) {
		validateJournalName(journalName);
		Path journalFile = resolveJournalFile(journalName);
		checkFileSize(journalFile, journalName);

		try (BufferedReader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8);
				CSVParser parser = CSV_FORMAT.parse(reader)) {
			validateHeaders(parser);
			return readTrades(parser);
		}
		catch (CsvJournalException exception) {
			throw exception;
		}
		catch (IllegalArgumentException exception) {
			throw new CsvJournalException(
					INVALID_HEADER,
					0,
					null,
					"journal header is invalid",
					exception);
		}
		catch (UncheckedIOException exception) {
			throw readFailure(journalName, exception.getCause());
		}
		catch (IOException exception) {
			throw readFailure(journalName, exception);
		}
	}

	private void validateJournalName(String journalName) {
		if (journalName == null || !VALID_JOURNAL_NAME.matcher(journalName).matches()) {
			throw new CsvJournalException(
					INVALID_JOURNAL_NAME,
					"journal name must contain only letters, digits, hyphens, or underscores");
		}
	}

	private Path resolveJournalFile(String journalName) {
		try {
			Path realRoot = journalRoot.toRealPath();
			Path candidate = journalRoot.resolve(journalName + ".csv").normalize();

			if (!candidate.startsWith(journalRoot)) {
				throw new CsvJournalException(
						PATH_OUTSIDE_ROOT,
						"journal path must stay inside the configured root");
			}
			if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
				throw new CsvJournalException(
						JOURNAL_NOT_FOUND,
						"journal does not exist: " + journalName);
			}

			Path realFile = candidate.toRealPath();
			if (!realFile.startsWith(realRoot)) {
				throw new CsvJournalException(
						PATH_OUTSIDE_ROOT,
						"journal path must stay inside the configured root");
			}
			if (!Files.isRegularFile(realFile)) {
				throw new CsvJournalException(
						INVALID_FILE_TYPE,
						"journal must be a regular CSV file");
			}
			return realFile;
		}
		catch (CsvJournalException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw readFailure(journalName, exception);
		}
	}

	private void checkFileSize(Path journalFile, String journalName) {
		try {
			if (Files.size(journalFile) > maxFileSizeBytes) {
				throw new CsvJournalException(
						FILE_TOO_LARGE,
						"journal exceeds the maximum file size");
			}
		}
		catch (CsvJournalException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw readFailure(journalName, exception);
		}
	}

	private static void validateHeaders(CSVParser parser) {
		List<String> headers = parser.getHeaderNames();
		if (headers.size() != CsvTradeSchema.HEADERS.size()
				|| !Set.copyOf(headers).equals(CsvTradeSchema.HEADER_SET)) {
			throw new CsvJournalException(
					INVALID_HEADER,
					"journal header must contain exactly the supported trade columns");
		}
	}

	private List<Trade> readTrades(CSVParser parser) {
		List<Trade> trades = new ArrayList<>();
		Set<String> tradeIds = new HashSet<>();
		CurrencyCode journalCurrency = null;

		for (CSVRecord record : parser) {
			long lineNumber = parser.getCurrentLineNumber();
			if (!record.isConsistent()) {
				throw new CsvJournalException(
						INVALID_FIELD,
						lineNumber,
						null,
						"row must contain exactly one value for every header",
						null);
			}
			if (trades.size() >= maxTrades) {
				throw new CsvJournalException(
						TOO_MANY_TRADES,
						lineNumber,
						null,
						"journal exceeds the maximum trade count",
						null);
			}

			Trade trade = mapper.map(record, lineNumber);
			if (!tradeIds.add(trade.tradeId())) {
				throw new CsvJournalException(
						DUPLICATE_TRADE_ID,
						lineNumber,
						CsvTradeSchema.TRADE_ID,
						"trade_id must be unique within a journal",
						null);
			}

			CurrencyCode tradeCurrency = trade.entryPrice().currency();
			if (journalCurrency == null) {
				journalCurrency = tradeCurrency;
			}
			else if (!journalCurrency.equals(tradeCurrency)) {
				throw new CsvJournalException(
						MIXED_CURRENCY,
						lineNumber,
						CsvTradeSchema.CURRENCY,
						"all trades in a journal must use the same currency",
						null);
			}
			trades.add(trade);
		}

		if (trades.isEmpty()) {
			throw new CsvJournalException(EMPTY_JOURNAL, "journal must contain at least one trade");
		}
		return List.copyOf(trades);
	}

	private static CsvJournalException readFailure(String journalName, IOException cause) {
		return new CsvJournalException(
				JOURNAL_READ_FAILED,
				0,
				null,
				"journal could not be read: " + journalName,
				cause);
	}
}
