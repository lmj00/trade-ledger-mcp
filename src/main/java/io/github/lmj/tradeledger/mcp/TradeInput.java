package io.github.lmj.tradeledger.mcp;

/**
 * MCP input contract for one already-closed trade. Decimal values are plain strings.
 */
public record TradeInput(
		String tradeId,
		String symbol,
		String market,
		String direction,
		String quantity,
		String entryPrice,
		String exitPrice,
		String fees,
		String currency,
		String openedAt,
		String closedAt,
		String strategy) {
}
