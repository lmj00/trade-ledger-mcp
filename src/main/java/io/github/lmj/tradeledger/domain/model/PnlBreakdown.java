package io.github.lmj.tradeledger.domain.model;

import java.util.Objects;

/**
 * Realized PnL result that makes fee inclusion explicit.
 */
public record PnlBreakdown(Money grossPnl, Money fees, Money netPnl) {

	public PnlBreakdown {
		Objects.requireNonNull(grossPnl, "gross PnL must not be null");
		Objects.requireNonNull(fees, "fees must not be null");
		Objects.requireNonNull(netPnl, "net PnL must not be null");

		if (fees.amount().signum() < 0) {
			throw new IllegalArgumentException("fees must be zero or positive");
		}
		if (!grossPnl.currency().equals(fees.currency())
				|| !grossPnl.currency().equals(netPnl.currency())) {
			throw new IllegalArgumentException("all PnL amounts must use the same currency");
		}
		if (!grossPnl.subtract(fees).equals(netPnl)) {
			throw new IllegalArgumentException("net PnL must equal gross PnL minus fees");
		}
	}
}
