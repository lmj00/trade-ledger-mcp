package io.github.lmj.tradeledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "trade-ledger.journal.root=src/test/resources/journals")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TradeLedgerMcpApplicationTests {

	@Autowired
	ApplicationContext applicationContext;

	@Autowired
	TradeJournalProperties journalProperties;

	@Test
	void contextLoads() {
		assertThat(journalProperties.root()).isAbsolute().isDirectory();
		assertThat(applicationContext.containsBean("upbitOrderClient")).isTrue();
		// Merely starting the server and registering tools must not construct a client
		// or require credentials / transport JVM options.
		assertThat(((ConfigurableApplicationContext) applicationContext)
				.getBeanFactory().containsSingleton("upbitOrderClient")).isFalse();
	}

	@Test
	void registersOnlyTwoCalculationToolsAndSingleReadOnlyOrderLookup() {
		assertThat(toolSpecifications()).extracting(specification -> specification.tool().name())
				.containsExactlyInAnyOrder("summarize_journal", "analyze_trades", "get_upbit_order");
		SyncToolSpecification specification = toolSpecifications().stream()
				.filter(candidate -> candidate.tool().name().equals("get_upbit_order"))
				.findFirst().orElseThrow();
		assertThat(asMap(specification.tool().inputSchema().get("properties")))
				.containsOnlyKeys("uuid");
		assertThat(specification.tool().inputSchema().get("required")).isEqualTo(List.of("uuid"));
		assertThat(asMap(asMap(specification.tool().inputSchema().get("properties")).get("uuid")))
				.containsEntry("type", "string");
		assertThat(specification.tool().annotations().readOnlyHint()).isTrue();
		assertThat(specification.tool().annotations().destructiveHint()).isFalse();
		assertThat(specification.tool().annotations().openWorldHint()).isTrue();
		assertThat(asMap(specification.tool().outputSchema().get("properties")))
				.containsKeys("orderId", "executedVolume", "paidFee", "trades");
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

	@ParameterizedTest
	@ValueSource(strings = {"duplicate-header", "missing-root"})
	void registeredSummaryDiscardsParserAndFilesystemDetails(
			String failureKind, @TempDir Path temporaryRoot) throws IOException {
		String marker = "synthetic-summary-c8v2";
		String rawHeader = marker + ",symbol,market,direction,quantity,entry_price,exit_price,fees,"
				+ "currency,opened_at,closed_at," + marker;
		Path root = failureKind.equals("missing-root") ? temporaryRoot.resolve(marker) : temporaryRoot;
		String description;
		if (failureKind.equals("duplicate-header")) {
			Files.writeString(root.resolve("invalid.csv"), rawHeader + "\n");
			description = "journal header is invalid";
		}
		else {
			assertThat(root).doesNotExist();
			description = "journal could not be read: invalid";
		}

		new ApplicationContextRunner()
				.withUserConfiguration(TradeLedgerMcpApplication.class)
				.withPropertyValues(
						"trade-ledger.journal.root=" + root,
						"spring.ai.mcp.server.type=SYNC",
						"spring.ai.mcp.server.stdio=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean("tradeJournalReader")).isInstanceOf(CsvTradeJournalReader.class);
					SyncToolSpecification specification = toolSpecifications(context).stream()
							.filter(candidate -> candidate.tool().name().equals("summarize_journal"))
							.findFirst()
							.orElseThrow();

					CallToolResult result = specification.callHandler().apply(null,
							CallToolRequest.builder("summarize_journal")
									.arguments(Map.of("journalName", "invalid"))
									.build());

					assertAll("registered summarize_journal " + failureKind + " response",
							() -> assertThat(result.isError()).isTrue(),
							() -> assertThat(result.content()).isNotEmpty().allSatisfy(content ->
									assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
											assertThat(text.text()).contains(description)
													.doesNotContain(marker, rawHeader, root.toString(), temporaryRoot.toString()))),
							() -> assertThat(String.valueOf(result.structuredContent()))
									.doesNotContain(marker, rawHeader, root.toString(), temporaryRoot.toString()));
				});
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

		// The mapper wrapper has a location; its innermost symbol exception has only "invalid symbol".
		// Check that the registered handler selects the innermost message.
		assertAll("registered analyze_trades invalid symbol response",
				() -> assertThat(result.isError()).isTrue(),
				() -> assertThat(result.content()).isNotEmpty(),
				() -> assertThat(result.content().toString()).contains("invalid symbol")
						.doesNotContain("trades[1].symbol"),
				() -> assertThat(result.content().toString())
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)),
				() -> assertThat(String.valueOf(result.structuredContent()))
						.doesNotContain(marker, marker.toUpperCase(Locale.ROOT)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "market", "direction", "openedAt", "closedAt" })
	void registeredAnalyzeTradesRejectsInvalidParsedFieldsWithoutExposingInput(String field) {
		String marker = "synthetic-analyze-" + field + "-i7y9";
		assertThat(marker.length()).isLessThanOrEqualTo(256);
		Map<String, Object> trade = structuredTrade("trade-1");
		trade.put(field, marker);
		String description = Set.of("market", "direction").contains(field)
				? "contains an unsupported value" : "must be an ISO-8601 timestamp with an offset";

		CallToolResult result = callAnalyzeTrades(analyzeTradesSpecification(),
				List.of(structuredTrade("trade-0"), trade));

		// Mapper tests assert location; this boundary checks the registered handler's response.
		assertAll("registered analyze_trades invalid " + field + " response",
				() -> assertThat(result.isError()).isTrue(),
				() -> assertThat(result.content()).isNotEmpty(),
				() -> assertThat(result.content().toString()).contains(description)
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
		return toolSpecifications(applicationContext);
	}

	private static List<SyncToolSpecification> toolSpecifications(ApplicationContext context) {
		Object bean = context.getBean("toolSpecs");
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
