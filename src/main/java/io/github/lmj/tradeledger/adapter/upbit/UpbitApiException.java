package io.github.lmj.tradeledger.adapter.upbit;

/** Contains only a safe category, never a transport/parser cause or response body. */
public final class UpbitApiException extends RuntimeException {

	private final UpbitErrorCode code;

	UpbitApiException(UpbitErrorCode code) {
		super("Upbit request failed: " + code.name(), null, false, true);
		this.code = code;
	}

	public UpbitErrorCode code() {
		return code;
	}
}
