package io.github.lmj.tradeledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

	@Test
	void analyzesOneHundredUniqueTradesAndRejectsOneHundredAndOne() {
		SyncToolSpecification specification = analyzeTradesSpecification();
		List<Map<String, Object>> trades = IntStream.range(0, 100)
				.mapToObj(index -> structuredTrade("trade-" + index))
				.toList();

		CallToolResult result = callAnalyzeTrades(specification, trades);

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsEntry("tradeCount", 100);

		List<Map<String, Object>> tooManyTrades = IntStream.range(0, 101)
				.mapToObj(index -> structuredTrade("trade-" + index))
				.toList();
		CallToolResult invalidResult = callAnalyzeTrades(specification, tooManyTrades);

		assertThat(invalidResult.isError()).isTrue();
		assertThat(invalidResult.content().toString()).contains("at most 100");
	}

	@Test
	void registeredAnalyzeTradesRejectsInvalidSymbolWithoutExposingInput() {
		String marker = "synthetic-symbol-e3u5";
		String raw = marker + "!";
		assertThat(raw.length()).isLessThanOrEqualTo(32);
		Map<String, Object> trade = structuredTrade("trade-1");
		trade.put("symbol", raw);

		CallToolResult result = callAnalyzeTrades(analyzeTradesSpecification(),
				List.of(structuredTrade("trade-0"), trade));

		// TradeInputMapperTest verifies the field location on the mapper exception.
		assertAll("registered analyze_trades invalid symbol response",
				() -> assertThat(result.isError()).isTrue(),
				() -> assertThat(result.content()).isNotEmpty(),
				() -> assertThat(result.content().toString()).contains("invalid symbol"),
				() -> assertThat(result.content().toString())
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)),
				() -> assertThat(String.valueOf(result.structuredContent()))
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)));
	}

	@Test
	void registeredAnalyzeTradesRejectsDuplicateIdWithoutExposingInput() {
		String marker = "synthetic-id-f4v6";

		CallToolResult result = callAnalyzeTrades(analyzeTradesSpecification(),
				List.of(structuredTrade(marker), structuredTrade("trade-1"), structuredTrade(marker)));

		assertAll("registered analyze_trades duplicate ID response",
				() -> assertThat(result.isError()).isTrue(),
				() -> assertThat(result.content()).isNotEmpty(),
				() -> assertThat(result.content().toString()).contains("unique", "index 2"),
				() -> assertThat(result.content().toString())
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)),
				() -> assertThat(String.valueOf(result.structuredContent()))
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "tradeId", "symbol", "market", "direction", "quantity", "entryPrice",
			"exitPrice", "fees", "currency", "openedAt", "closedAt", "strategy" })
	void registeredAnalyzeTradesReturnsFieldLengthErrors(String field) {
		Map<String, Object> trade = structuredTrade("trade-1");
		int limit = Set.of("quantity", "entryPrice", "exitPrice", "fees").contains(field) ? 128 : 256;
		String raw = " ".repeat(limit) + trade.get(field);
		trade.put(field, raw);

		CallToolResult result = callAnalyzeTrades(analyzeTradesSpecification(),
				List.of(structuredTrade("trade-0"), trade));

		assertThat(result.isError()).isTrue();
		assertThat(result.content().toString())
				.contains("trades[1]." + field)
				.contains("at most " + limit + " characters")
				.doesNotContain(raw);
	}

	private SyncToolSpecification analyzeTradesSpecification() {
		return toolSpecifications().stream()
				.filter(candidate -> candidate.tool().name().equals("analyze_trades"))
				.findFirst()
				.orElseThrow();
	}

	private static CallToolResult callAnalyzeTrades(
			SyncToolSpecification specification, List<Map<String, Object>> trades) {
		return specification.callHandler().apply(null,
				CallToolRequest.builder("analyze_trades")
						.arguments(Map.of("trades", trades))
						.build());
	}

	private static Map<String, Object> structuredTrade(String tradeId) {
		Map<String, Object> trade = new LinkedHashMap<>();
		trade.put("tradeId", tradeId);
		trade.put("symbol", "BTC-USDT");
		trade.put("market", "CRYPTO");
		trade.put("direction", "LONG");
		trade.put("quantity", "1");
		trade.put("entryPrice", "100");
		trade.put("exitPrice", "110");
		trade.put("fees", "1");
		trade.put("currency", "USDT");
		trade.put("openedAt", "2026-08-01T09:00:00+09:00");
		trade.put("closedAt", "2026-08-01T11:00:00+09:00");
		trade.put("strategy", "breakout");
		return trade;
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
