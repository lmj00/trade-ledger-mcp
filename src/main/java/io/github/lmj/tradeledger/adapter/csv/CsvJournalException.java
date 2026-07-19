package io.github.lmj.tradeledger.adapter.csv;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Structured failure raised while locating, parsing, or validating a CSV journal.
 */
public final class CsvJournalException extends RuntimeException {

	private final CsvJournalErrorCode code;
	private final long lineNumber;
	private final String field;

	CsvJournalException(CsvJournalErrorCode code, String message) {
		this(code, 0, null, message, null);
	}

	CsvJournalException(
			CsvJournalErrorCode code,
			long lineNumber,
			String field,
			String message,
			Throwable cause) {
		super(message, cause);
		this.code = Objects.requireNonNull(code, "code must not be null");
		this.lineNumber = lineNumber;
		this.field = field;
	}

	public CsvJournalErrorCode code() {
		return code;
	}

	public OptionalLong lineNumber() {
		return lineNumber > 0 ? OptionalLong.of(lineNumber) : OptionalLong.empty();
	}

	public Optional<String> field() {
		return Optional.ofNullable(field);
	}
}
