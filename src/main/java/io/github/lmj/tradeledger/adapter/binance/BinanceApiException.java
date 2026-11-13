package io.github.lmj.tradeledger.adapter.binance;

/** Retains only a safe category; causes and suppressed exceptions are disabled. */
public final class BinanceApiException extends RuntimeException {
	private final BinanceErrorCode code;

	BinanceApiException(BinanceErrorCode code) {
		super("Binance request failed: " + code.name(), null, false, true);
		this.code = code;
	}

	public BinanceErrorCode code() {
		return code;
	}
}
