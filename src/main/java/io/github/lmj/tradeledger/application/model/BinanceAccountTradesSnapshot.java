package io.github.lmj.tradeledger.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable fills from one supplied Binance Spot account/symbol response.
 * Identity is scoped by (accountAlias, serviceId, symbol, execution id), never by
 * trade id alone. accountAlias is a caller-provided local label, not a credential.
 * Amount strings retain their original precision and scale; commissionAsset is
 * preserved without fee aggregation, currency inference or conversion.
 *
 * <p>This models one recent or inclusive-fromId result, without automatic
 * pagination, completed trades or PnL. possiblyTruncated means only that the
 * response size equals the requested limit. A short or empty response does not
 * establish that the full account history has been collected.
 *
 * @see <a href="https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account">Binance Spot account trade list</a>
 */
public record BinanceAccountTradesSnapshot(String serviceId, String accountAlias, String symbol,
		List<Execution> trades, boolean possiblyTruncated) {
	public BinanceAccountTradesSnapshot {
		Objects.requireNonNull(serviceId);
		Objects.requireNonNull(accountAlias);
		Objects.requireNonNull(symbol);
		trades = List.copyOf(trades);
	}

	/** Source field names are retained; time is the supplied epoch milliseconds as an Instant. */
	public record Execution(String symbol, long id, long orderId, long orderListId,
			String price, String qty, String quoteQty, String commission, String commissionAsset,
			Instant time, boolean isBuyer, boolean isMaker, boolean isBestMatch) {
		public Execution {
			Objects.requireNonNull(symbol);
			Objects.requireNonNull(price);
			Objects.requireNonNull(qty);
			Objects.requireNonNull(quoteQty);
			Objects.requireNonNull(commission);
			Objects.requireNonNull(commissionAsset);
			Objects.requireNonNull(time);
		}
	}
}
