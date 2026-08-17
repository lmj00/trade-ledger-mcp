package io.github.lmj.tradeledger.mcp;

import java.util.Objects;

import io.github.lmj.tradeledger.application.service.SummarizeJournalService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public final class TradeLedgerTools {

	private final SummarizeJournalService summarizeJournalService;

	public TradeLedgerTools(SummarizeJournalService summarizeJournalService) {
		this.summarizeJournalService = Objects.requireNonNull(
				summarizeJournalService,
				"summarize journal service must not be null");
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
}
