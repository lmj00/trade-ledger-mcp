package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import io.github.lmj.tradeledger.application.model.OrderExecutionSnapshot;

/** A single order and its fills, with exact decimal strings and no inferred PnL. */
public record UpbitOrderResponse(
		String serviceId,
		String orderId,
		String market,
		String side,
		String orderType,
		String state,
		String createdAt,
		String executedVolume,
		String paidFee,
		List<ExecutionResponse> trades) {

	public UpbitOrderResponse {
		trades = List.copyOf(trades);
	}

	public static UpbitOrderResponse from(OrderExecutionSnapshot order) {
		Objects.requireNonNull(order, "order must not be null");
		return new UpbitOrderResponse(order.serviceId(), order.orderId(), order.market(),
				order.side(), order.orderType(), order.state(), order.createdAt().toString(),
				order.executedVolume(), order.paidFee(),
				order.trades().stream().map(trade -> new ExecutionResponse(
						trade.tradeId(), trade.market(), trade.side(), trade.price(), trade.volume(),
						trade.funds(), trade.createdAt().toString())).toList());
	}

	/** Fees belong to the order; they are not allocated to individual fills. */
	public record ExecutionResponse(
			String tradeId,
			String market,
			String side,
			String price,
			String volume,
			String funds,
			String createdAt) {
	}
}
