package io.github.lmj.tradeledger.config;

import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.application.service.AnalyzeTradesService;
import io.github.lmj.tradeledger.application.service.SummarizeJournalService;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradeJournalProperties.class)
class TradeLedgerConfiguration {

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
