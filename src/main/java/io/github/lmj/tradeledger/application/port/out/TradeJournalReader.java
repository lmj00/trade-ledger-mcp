package io.github.lmj.tradeledger.application.port.out;

import java.util.List;

import io.github.lmj.tradeledger.domain.model.Trade;

/**
 * Loads a named journal as validated, fully closed trades.
 */
@FunctionalInterface
public interface TradeJournalReader {

	List<Trade> read(String journalName);
}
