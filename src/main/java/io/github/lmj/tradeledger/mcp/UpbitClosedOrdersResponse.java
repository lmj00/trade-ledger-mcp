package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.model.ClosedOrdersSnapshot;

/** Exact summaries only; even short or empty results do not establish complete history. */
public record UpbitClosedOrdersResponse(
		String serviceId, List<OrderResponse> orders, boolean possiblyTruncated) {

	public UpbitClosedOrdersResponse {
		orders = List.copyOf(orders);
	}

	public static UpbitClosedOrdersResponse from(ClosedOrdersSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new UpbitClosedOrdersResponse(snapshot.serviceId(), snapshot.orders().stream()
				.map(order -> new OrderResponse(order.orderId(), order.market(), order.side(),
						order.orderType(), order.state(), order.createdAt().toString(),
						order.executedVolume(), order.paidFee(), order.tradesCount().toString()))
				.toList(), snapshot.possiblyTruncated());
	}

	/** Counts are decimal strings too, including values beyond JavaScript and int64 precision. */
	public record OrderResponse(String orderId, String market, String side, String orderType,
			String state, String createdAt, String executedVolume, String paidFee, String tradesCount) {
	}
}
