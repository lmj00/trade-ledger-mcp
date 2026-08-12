package io.github.lmj.tradeledger.application.model;

import java.util.Objects;

import io.github.lmj.tradeledger.domain.model.Money;

/**
 * Immutable result of summarizing realized PnL for one trade journal.
 */
public record JournalSummary(
		String journalName,
		int tradeCount,
		int winningTrades,
		int losingTrades,
		int breakEvenTrades,
		Money totalGrossPnl,
		Money totalFees,
		Money totalNetPnl) {

	public JournalSummary {
		Objects.requireNonNull(journalName, "journal name must not be null");
		journalName = journalName.trim();
		if (journalName.isEmpty()) {
			throw new IllegalArgumentException("journal name must not be blank");
		}

		if (tradeCount < 0 || winningTrades < 0 || losingTrades < 0 || breakEvenTrades < 0) {
			throw new IllegalArgumentException("trade counts must not be negative");
		}
		long classifiedTrades = (long) winningTrades + losingTrades + breakEvenTrades;
		if (classifiedTrades != tradeCount) {
			throw new IllegalArgumentException(
					"winning, losing, and break-even trades must add up to trade count");
		}

		Objects.requireNonNull(totalGrossPnl, "total gross PnL must not be null");
		Objects.requireNonNull(totalFees, "total fees must not be null");
		Objects.requireNonNull(totalNetPnl, "total net PnL must not be null");
		if (totalFees.amount().signum() < 0) {
			throw new IllegalArgumentException("total fees must not be negative");
		}
		if (!totalGrossPnl.currency().equals(totalFees.currency())
				|| !totalGrossPnl.currency().equals(totalNetPnl.currency())) {
			throw new IllegalArgumentException("all journal totals must use the same currency");
		}
		if (!totalGrossPnl.subtract(totalFees).equals(totalNetPnl)) {
			throw new IllegalArgumentException(
					"total net PnL must equal total gross PnL minus total fees");
		}
	}
}
