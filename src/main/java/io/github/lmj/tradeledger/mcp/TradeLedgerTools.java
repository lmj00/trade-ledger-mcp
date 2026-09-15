package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.service.AnalyzeTradesService;
import io.github.lmj.tradeledger.application.service.SummarizeJournalService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public final class TradeLedgerTools {

	private final SummarizeJournalService summarizeJournalService;
	private final AnalyzeTradesService analyzeTradesService;
	private final TradeInputMapper tradeInputMapper;

	public TradeLedgerTools(
			SummarizeJournalService summarizeJournalService,
			AnalyzeTradesService analyzeTradesService) {
		this.summarizeJournalService = Objects.requireNonNull(
				summarizeJournalService,
				"summarize journal service must not be null");
		this.analyzeTradesService = Objects.requireNonNull(
				analyzeTradesService,
				"analyze trades service must not be null");
		this.tradeInputMapper = new TradeInputMapper();
	}

	@McpTool(
			name = "summarize_journal",
			title = "Summarize trade journal",
			description = "Summarize realized profit and loss for a validated local trade journal.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = false))
	public JournalSummaryResponse summarizeJournal(
			@McpToolParam(
					description = "Journal name without a path or file extension, for example sample-trades.",
					required = true)
			String journalName) {
		return JournalSummaryResponse.from(summarizeJournalService.summarize(journalName));
	}

	@McpTool(
			name = "analyze_trades",
			title = "Analyze structured closed trades",
			description = "Analyze realized profit and loss for 1 to 100 already-closed trades supplied as structured input.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = false))
	public TradeAnalysisResponse analyzeTrades(
			@McpToolParam(
					description = "Closed trades with decimal values encoded as plain strings and timestamps encoded as ISO-8601 with an offset.",
					required = true)
			List<TradeInput> trades) {
		return TradeAnalysisResponse.from(
				analyzeTradesService.analyze(tradeInputMapper.map(trades)));
	}
}
