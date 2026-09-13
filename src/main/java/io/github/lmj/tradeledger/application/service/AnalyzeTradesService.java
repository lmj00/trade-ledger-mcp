package io.github.lmj.tradeledger.application.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.lmj.tradeledger.application.model.TradeAnalysis;
import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.PnlBreakdown;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;

/**
 * Applies deterministic realized PnL aggregation to validated closed trades.
 */
public final class AnalyzeTradesService {

	private final RealizedPnlCalculator pnlCalculator;

	public AnalyzeTradesService(RealizedPnlCalculator pnlCalculator) {
		this.pnlCalculator = Objects.requireNonNull(
				pnlCalculator,
				"PnL calculator must not be null");
	}

	public TradeAnalysis analyze(List<Trade> trades) {
		Objects.requireNonNull(trades, "trades must not be null");
		if (trades.isEmpty()) {
			throw new IllegalArgumentException("at least one trade is required");
		}

		CurrencyCode currency = requireTrade(trades.getFirst(), 0).entryPrice().currency();
		Money totalGrossPnl = new Money(BigDecimal.ZERO, currency);
		Money totalFees = new Money(BigDecimal.ZERO, currency);
		Money totalNetPnl = new Money(BigDecimal.ZERO, currency);
		Set<String> tradeIds = new HashSet<>();
		int winningTrades = 0;
		int losingTrades = 0;
		int breakEvenTrades = 0;

		for (int index = 0; index < trades.size(); index++) {
			Trade trade = requireTrade(trades.get(index), index);
			if (!tradeIds.add(trade.tradeId())) {
				throw new IllegalArgumentException("trade IDs must be unique: " + trade.tradeId());
			}
			if (!currency.equals(trade.entryPrice().currency())) {
				throw new IllegalArgumentException("all trades must use the same currency");
			}

			PnlBreakdown pnl = pnlCalculator.calculate(trade);
			totalGrossPnl = totalGrossPnl.add(pnl.grossPnl());
			totalFees = totalFees.add(pnl.fees());
			totalNetPnl = totalNetPnl.add(pnl.netPnl());

			int netPnlSign = pnl.netPnl().amount().signum();
			if (netPnlSign > 0) {
				winningTrades++;
			}
			else if (netPnlSign < 0) {
				losingTrades++;
			}
			else {
				breakEvenTrades++;
			}
		}

		return new TradeAnalysis(
				trades.size(),
				winningTrades,
				losingTrades,
				breakEvenTrades,
				totalGrossPnl,
				totalFees,
				totalNetPnl);
	}

	private static Trade requireTrade(Trade trade, int index) {
		return Objects.requireNonNull(trade, "trade at index " + index + " must not be null");
	}
}
