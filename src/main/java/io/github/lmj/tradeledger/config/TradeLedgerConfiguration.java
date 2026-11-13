package io.github.lmj.tradeledger.config;

import java.util.List;

import io.github.lmj.tradeledger.adapter.binance.BinanceAccountTradesClient;
import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
import io.github.lmj.tradeledger.adapter.toss.TossErrorCode;
import io.github.lmj.tradeledger.adapter.toss.TossOrderClient;
import io.github.lmj.tradeledger.adapter.upbit.UpbitOrderClient;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.application.service.AnalyzeTradesService;
import io.github.lmj.tradeledger.application.service.SummarizeJournalService;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradeJournalProperties.class)
class TradeLedgerConfiguration {

	@Bean
	static BeanPostProcessor upbitOrderContentPostProcessor() {
		return new BeanPostProcessor() {
			private final JsonMapper mapper = JsonMapper.builder().build();

			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (!"toolSpecs".equals(beanName) || !(bean instanceof List<?> specifications)) {
					return bean;
				}
				return specifications.stream().map(candidate -> {
					if (!(candidate instanceof SyncToolSpecification specification)
							|| !("get_upbit_order".equals(specification.tool().name())
									|| "get_upbit_order_executions".equals(specification.tool().name())
									|| "get_upbit_closed_orders".equals(specification.tool().name())
									|| "get_binance_account_trades".equals(specification.tool().name())
									|| "get_binance_account_executions".equals(specification.tool().name())
									|| "get_toss_order".equals(specification.tool().name()))) {
						return candidate;
					}
					String service = "get_binance_account_trades".equals(specification.tool().name())
								|| "get_binance_account_executions".equals(specification.tool().name())
							? "Binance" : "get_toss_order".equals(specification.tool().name()) ? "Toss" : "Upbit";
					// Preserve the generated schemas and the existing safe error handlers.
					// The annotation adapter supplies structuredContent for these DTOs.
					return SyncToolSpecification.builder().tool(specification.tool())
							.callHandler((exchange, request) -> {
								// Reject wrong JSON types before the annotation adapter can coerce
								// them to strings or expose conversion diagnostics containing input.
								if ("Toss".equals(service) && (request.arguments() == null
										|| !(request.arguments().get("orderId") instanceof String))) {
									return tossErrorResult("BAD_REQUEST");
								}
								CallToolResult result;
								try {
									result = specification.callHandler().apply(exchange, request);
								}
								catch (RuntimeException exception) {
									if (!"Toss".equals(service)) { throw exception; }
									return tossErrorResult("INTERNAL_ERROR");
								}
								if (Boolean.TRUE.equals(result.isError())) {
									if ("Toss".equals(service)) {
										// Retain only a whitelisted category from the annotation
										// handler, dropping any adapter prefix or partial output.
										for (TossErrorCode code : TossErrorCode.values()) {
											if (result.content().stream().anyMatch(content -> content instanceof TextContent text
													&& text.text().contains("Toss request failed: " + code.name()))) {
												return tossErrorResult(code.name());
											}
										}
										return tossErrorResult("INTERNAL_ERROR");
									}
									return result;
								}
								try {
									return CallToolResult.builder()
											.content(List.of(new TextContent(mapper.writeValueAsString(result.structuredContent()))))
											.structuredContent(result.structuredContent()).isError(false).build();
								}
								catch (RuntimeException exception) {
									return CallToolResult.builder().isError(true)
											.content(List.of(new TextContent(service + " request failed: INTERNAL_ERROR"))).build();
								}
							}).build();
				}).toList();
			}
		};
	}

	private static CallToolResult tossErrorResult(String category) {
		return CallToolResult.builder().isError(true)
				.content(List.of(new TextContent("Toss request failed: " + category))).build();
	}

	// Credentials and transport startup options are needed only when querying an exchange.
	@Bean
	@Lazy
	UpbitOrderClient upbitOrderClient() {
		return new UpbitOrderClient();
	}

	@Bean(destroyMethod = "close")
	@Lazy
	BinanceAccountTradesClient binanceAccountTradesClient() {
		return new BinanceAccountTradesClient();
	}

	@Bean(destroyMethod = "close")
	@Lazy
	TossOrderClient tossOrderClient() {
		return new TossOrderClient();
	}

	@Bean
	TradeJournalReader tradeJournalReader(TradeJournalProperties properties) {
		return new CsvTradeJournalReader(properties.root());
	}

	@Bean
	RealizedPnlCalculator realizedPnlCalculator() {
		return new RealizedPnlCalculator();
	}

	@Bean
	AnalyzeTradesService analyzeTradesService(RealizedPnlCalculator pnlCalculator) {
		return new AnalyzeTradesService(pnlCalculator);
	}

	@Bean
	SummarizeJournalService summarizeJournalService(
			TradeJournalReader journalReader,
			AnalyzeTradesService analyzeTradesService) {
		return new SummarizeJournalService(journalReader, analyzeTradesService);
	}
}
