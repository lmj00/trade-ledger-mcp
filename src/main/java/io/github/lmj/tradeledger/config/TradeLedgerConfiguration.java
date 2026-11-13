package io.github.lmj.tradeledger.config;

import java.util.List;

import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
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
									|| "get_upbit_closed_orders".equals(specification.tool().name()))) {
						return candidate;
					}
					// Preserve the generated schema and safe error handler. The annotation
					// adapter supplies structuredContent only for these DTO return types.
					return SyncToolSpecification.builder().tool(specification.tool())
							.callHandler((exchange, request) -> {
								CallToolResult result = specification.callHandler().apply(exchange, request);
								if (Boolean.TRUE.equals(result.isError())) {
									return result;
								}
								try {
									return CallToolResult.builder()
											.content(List.of(new TextContent(mapper.writeValueAsString(result.structuredContent()))))
											.structuredContent(result.structuredContent()).isError(false).build();
								}
								catch (RuntimeException exception) {
									return CallToolResult.builder().isError(true)
											.content(List.of(new TextContent("Upbit request failed: INTERNAL_ERROR"))).build();
								}
							}).build();
				}).toList();
			}
		};
	}

	// Credentials and transport startup options are needed only when querying Upbit.
	@Bean
	@Lazy
	UpbitOrderClient upbitOrderClient() {
		return new UpbitOrderClient();
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
