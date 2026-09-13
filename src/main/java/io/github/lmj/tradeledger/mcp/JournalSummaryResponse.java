package io.github.lmj.tradeledger.mcp;

import java.util.Objects;

import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.application.model.TradeAnalysis;

/**
 * MCP response for a journal summary, with decimal amounts encoded without precision loss.
 */
public record JournalSummaryResponse(
		String journalName,
		int tradeCount,
		int winningTrades,
		int losingTrades,
		int breakEvenTrades,
		String currency,
		String totalGrossPnl,
		String totalFees,
		String totalNetPnl) {

	public static JournalSummaryResponse from(JournalSummary summary) {
		Objects.requireNonNull(summary, "journal summary must not be null");
		TradeAnalysis analysis = summary.analysis();
		return new JournalSummaryResponse(
				summary.journalName(),
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
