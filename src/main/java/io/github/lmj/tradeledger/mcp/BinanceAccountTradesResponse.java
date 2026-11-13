package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot;

/** Recent fills only; even short or empty results do not establish complete history. */
public record BinanceAccountTradesResponse(String serviceId, String accountAlias, String symbol,
		List<ExecutionResponse> trades, boolean possiblyTruncated) {

	public BinanceAccountTradesResponse {
		trades = List.copyOf(trades);
	}

	public static BinanceAccountTradesResponse from(BinanceAccountTradesSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new BinanceAccountTradesResponse(snapshot.serviceId(), snapshot.accountAlias(), snapshot.symbol(),
				snapshot.trades().stream().map(trade -> new ExecutionResponse(trade.symbol(),
						Long.toString(trade.id()), Long.toString(trade.orderId()), Long.toString(trade.orderListId()),
						trade.price(), trade.qty(), trade.quoteQty(), trade.commission(), trade.commissionAsset(),
						trade.time().toString(), trade.isBuyer(), trade.isMaker(), trade.isBestMatch())).toList(),
				snapshot.possiblyTruncated());
	}

	/** Decimal ID strings avoid consumer-side precision loss; amount strings retain their scale. */
	public record ExecutionResponse(String symbol, String id, String orderId, String orderListId,
			String price, String qty, String quoteQty, String commission, String commissionAsset,
			String time, boolean isBuyer, boolean isMaker, boolean isBestMatch) {
	}
}
