package io.github.lmj.tradeledger.application.model;

import java.util.Objects;

/**
 * Immutable result of summarizing realized PnL for one trade journal.
 */
public record JournalSummary(
		String journalName,
		TradeAnalysis analysis) {

	public JournalSummary {
		Objects.requireNonNull(journalName, "journal name must not be null");
		journalName = journalName.trim();
		if (journalName.isEmpty()) {
			throw new IllegalArgumentException("journal name must not be blank");
		}

		Objects.requireNonNull(analysis, "trade analysis must not be null");
	}
}
