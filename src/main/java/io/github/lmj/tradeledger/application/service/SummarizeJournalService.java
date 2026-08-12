package io.github.lmj.tradeledger.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.model.JournalSummary;
import io.github.lmj.tradeledger.application.port.out.TradeJournalReader;
import io.github.lmj.tradeledger.domain.model.CurrencyCode;
import io.github.lmj.tradeledger.domain.model.Money;
import io.github.lmj.tradeledger.domain.model.PnlBreakdown;
import io.github.lmj.tradeledger.domain.model.Trade;
import io.github.lmj.tradeledger.domain.service.RealizedPnlCalculator;

/**
 * Coordinates journal loading and deterministic realized PnL aggregation.
 */
public final class SummarizeJournalService {

	private final TradeJournalReader journalReader;
	private final RealizedPnlCalculator pnlCalculator;

	public SummarizeJournalService(
			TradeJournalReader journalReader,
			RealizedPnlCalculator pnlCalculator) {
		this.journalReader = Objects.requireNonNull(journalReader, "journal reader must not be null");
		this.pnlCalculator = Objects.requireNonNull(pnlCalculator, "PnL calculator must not be null");
	}

	public JournalSummary summarize(String journalName) {
		Objects.requireNonNull(journalName, "journal name must not be null");
		List<Trade> trades = Objects.requireNonNull(
				journalReader.read(journalName),
				"journal reader must not return null");
		if (trades.isEmpty()) {
			throw new IllegalStateException("journal reader must return at least one trade");
		}

		CurrencyCode currency = trades.getFirst().entryPrice().currency();
		Money totalGrossPnl = new Money(BigDecimal.ZERO, currency);
		Money totalFees = new Money(BigDecimal.ZERO, currency);
		Money totalNetPnl = new Money(BigDecimal.ZERO, currency);
		int winningTrades = 0;
		int losingTrades = 0;
		int breakEvenTrades = 0;

		for (Trade trade : trades) {
			PnlBreakdown pnl = pnlCalculator.calculate(trade);
			totalGrossPnl = totalGrossPnl.add(pnl.grossPnl());
			totalFees = totalFees.add(pnl.fees());
			totalNetPnl = totalNetPnl.add(pnl.netPnl());

			int netPnlSign = pnl.netPnl().amount().signum();
			if (netPnlSign > 0) {
				winningTrades++;
			}
			else if (netPnlSign < 0) {
				losingTrades++;
			}
			else {
				breakEvenTrades++;
			}
		}

		return new JournalSummary(
				journalName,
				trades.size(),
				winningTrades,
				losingTrades,
				breakEvenTrades,
				totalGrossPnl,
				totalFees,
				totalNetPnl);
	}
}
