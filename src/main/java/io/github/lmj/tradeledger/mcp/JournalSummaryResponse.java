package io.github.lmj.tradeledger.mcp;

import java.util.Objects;

import io.github.lmj.tradeledger.application.model.JournalSummary;

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
		return new JournalSummaryResponse(
				summary.journalName(),
				summary.tradeCount(),
				summary.winningTrades(),
				summary.losingTrades(),
				summary.breakEvenTrades(),
				summary.totalNetPnl().currency().value(),
				summary.totalGrossPnl().amount().toPlainString(),
				summary.totalFees().amount().toPlainString(),
				summary.totalNetPnl().amount().toPlainString());
	}
}
