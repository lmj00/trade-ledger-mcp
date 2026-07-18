package io.github.lmj.tradeledger.domain.service;

import java.util.Objects;

import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.PnlBreakdown;
import io.github.lmj.tradeledger.domain.model.Trade;

/**
 * Calculates deterministic realized PnL for a fully closed trade.
 */
public final class RealizedPnlCalculator {

	public PnlBreakdown calculate(Trade trade) {
		Objects.requireNonNull(trade, "trade must not be null");

		Money priceDifference = switch (trade.direction()) {
			case LONG -> trade.exitPrice().subtract(trade.entryPrice());
			case SHORT -> trade.entryPrice().subtract(trade.exitPrice());
		};
		Money grossPnl = priceDifference.multiply(trade.quantity());
		Money netPnl = grossPnl.subtract(trade.fees());

		return new PnlBreakdown(grossPnl, trade.fees(), netPnl);
	}
}
