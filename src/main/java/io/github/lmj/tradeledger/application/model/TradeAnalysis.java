package io.github.lmj.tradeledger.application.model;

import java.util.Objects;

import io.github.lmj.tradeledger.domain.model.Money;

/**
 * Immutable aggregate of realized profit and loss for a group of closed trades.
 */
public record TradeAnalysis(
		int tradeCount,
		int winningTrades,
		int losingTrades,
		int breakEvenTrades,
		Money totalGrossPnl,
		Money totalFees,
		Money totalNetPnl) {

	public TradeAnalysis {
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
			throw new IllegalArgumentException("all trade analysis totals must use the same currency");
		}
		if (!totalGrossPnl.subtract(totalFees).equals(totalNetPnl)) {
			throw new IllegalArgumentException(
					"total net PnL must equal total gross PnL minus total fees");
		}
	}
}
