package io.github.lmj.tradeledger.mcp;

import java.util.Objects;

import io.github.lmj.tradeledger.application.model.TradeAnalysis;

/**
 * MCP response for structured trade analysis, with exact decimal strings.
 */
public record TradeAnalysisResponse(
		int tradeCount,
		int winningTrades,
		int losingTrades,
		int breakEvenTrades,
		String currency,
		String totalGrossPnl,
		String totalFees,
		String totalNetPnl) {

	public static TradeAnalysisResponse from(TradeAnalysis analysis) {
		Objects.requireNonNull(analysis, "trade analysis must not be null");
		return new TradeAnalysisResponse(
				analysis.tradeCount(),
				analysis.winningTrades(),
				analysis.losingTrades(),
				analysis.breakEvenTrades(),
				analysis.totalNetPnl().currency().value(),
				analysis.totalGrossPnl().amount().toPlainString(),
				analysis.totalFees().amount().toPlainString(),
				analysis.totalNetPnl().amount().toPlainString());
	}
}
