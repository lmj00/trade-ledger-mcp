package io.github.lmj.tradeledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "trade-ledger.journal.root=src/test/resources/journals")
class TradeLedgerMcpApplicationTests {

	@Autowired
	ApplicationContext applicationContext;

	@Autowired
	TradeJournalProperties journalProperties;

	@Test
	void contextLoads() {
		assertThat(journalProperties.root()).isAbsolute().isDirectory();
	}

	@Test
	void registersAndCallsReadOnlyJournalSummaryTool() {
		SyncToolSpecification specification = toolSpecifications().stream()
				.filter(candidate -> candidate.tool().name().equals("summarize_journal"))
				.findFirst()
				.orElseThrow();

		assertThat(asMap(specification.tool().inputSchema().get("properties")))
				.containsKey("journalName");
		assertThat(asMap(specification.tool().outputSchema().get("properties")))
				.containsKeys("journalName", "totalGrossPnl", "totalFees", "totalNetPnl");
		assertThat(specification.tool().annotations().readOnlyHint()).isTrue();
		assertThat(specification.tool().annotations().destructiveHint()).isFalse();
		assertThat(specification.tool().annotations().idempotentHint()).isTrue();
		assertThat(specification.tool().annotations().openWorldHint()).isFalse();

		CallToolResult result = specification.callHandler().apply(
				null,
				CallToolRequest.builder("summarize_journal")
						.arguments(Map.of("journalName", "sample-trades"))
						.build());

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent()))
				.containsEntry("journalName", "sample-trades")
				.containsEntry("tradeCount", 3)
				.containsEntry("winningTrades", 2)
				.containsEntry("losingTrades", 1)
				.containsEntry("currency", "USDT")
				.containsEntry("totalNetPnl", "355.78645");

		CallToolResult invalidResult = specification.callHandler().apply(
				null,
				CallToolRequest.builder("summarize_journal")
						.arguments(Map.of("journalName", "../private"))
						.build());

		assertThat(invalidResult.isError()).isTrue();
	}

	private List<SyncToolSpecification> toolSpecifications() {
		Object bean = applicationContext.getBean("toolSpecs");
		assertThat(bean).isInstanceOf(List.class);
		return ((List<?>) bean).stream()
				.filter(SyncToolSpecification.class::isInstance)
				.map(SyncToolSpecification.class::cast)
				.toList();
	}

	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		Map<String, Object> result = new LinkedHashMap<>();
		((Map<?, ?>) value).forEach((key, entryValue) -> {
			assertThat(key).isInstanceOf(String.class);
			result.put((String) key, entryValue);
		});
		return result;
	}
}
