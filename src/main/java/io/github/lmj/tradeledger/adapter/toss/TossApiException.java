package io.github.lmj.tradeledger.adapter.toss;

/** Retains only a safe category; causes and suppressed exceptions are disabled. */
public final class TossApiException extends RuntimeException {
	private final TossErrorCode code;

	TossApiException(TossErrorCode code) {
		super("Toss request failed: " + code.name(), null, false, true);
		this.code = code;
	}

	public TossErrorCode code() {
		return code;
	}
}
