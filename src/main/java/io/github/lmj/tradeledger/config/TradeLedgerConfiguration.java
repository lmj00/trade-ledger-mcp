package io.github.lmj.tradeledger.config;

import io.github.lmj.tradeledger.adapter.csv.CsvTradeJournalReader;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
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
	SummarizeJournalService summarizeJournalService(
			TradeJournalReader journalReader,
			RealizedPnlCalculator pnlCalculator) {
		return new SummarizeJournalService(journalReader, pnlCalculator);
	}
}
