package io.github.lmj.tradeledger.adapter.csv;

/**
 * Stable error categories returned when a CSV journal cannot be read.
 */
public enum CsvJournalErrorCode {
	INVALID_JOURNAL_NAME,
	JOURNAL_NOT_FOUND,
	PATH_OUTSIDE_ROOT,
	INVALID_FILE_TYPE,
	FILE_TOO_LARGE,
	JOURNAL_READ_FAILED,
	INVALID_HEADER,
	EMPTY_JOURNAL,
	TOO_MANY_TRADES,
	INVALID_FIELD,
	DUPLICATE_TRADE_ID,
	MIXED_CURRENCY
}
