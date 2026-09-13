package io.github.lmj.tradeledger.application.service;

import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.domain.model.Trade;

/**
 * Coordinates journal loading and deterministic realized PnL aggregation.
 */
public final class SummarizeJournalService {

	private final TradeJournalReader journalReader;
	private final AnalyzeTradesService analyzeTradesService;

	public SummarizeJournalService(
			TradeJournalReader journalReader,
			AnalyzeTradesService analyzeTradesService) {
		this.journalReader = Objects.requireNonNull(journalReader, "journal reader must not be null");
		this.analyzeTradesService = Objects.requireNonNull(
				analyzeTradesService,
				"analyze trades service must not be null");
	}

	public JournalSummary summarize(String journalName) {
		Objects.requireNonNull(journalName, "journal name must not be null");
		List<Trade> trades = Objects.requireNonNull(
				journalReader.read(journalName),
				"journal reader must not return null");
		if (trades.isEmpty()) {
			throw new IllegalStateException("journal reader must return at least one trade");
		}
		return new JournalSummary(journalName, analyzeTradesService.analyze(trades));
	}
}
