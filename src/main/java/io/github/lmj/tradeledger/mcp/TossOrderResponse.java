package io.github.lmj.tradeledger.mcp;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Pure projection of the order and cumulative summary; no invented fills or PnL. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TossOrderResponse(
		String serviceId,
		String orderId,
		String symbol,
		String side,
		String orderType,
		String timeInForce,
		String status,
		@Schema(nullable = true) @Nullable String price,
		String quantity,
		@Schema(nullable = true) @Nullable String orderAmount,
		String currency,
		String orderedAt,
		@Schema(nullable = true) @Nullable String canceledAt,
		ExecutionResponse execution) {

	public static TossOrderResponse from(TossOrderSnapshot order) {
		Objects.requireNonNull(order, "order must not be null");
		var execution = order.execution();
		return new TossOrderResponse("toss", order.orderId(), order.symbol(), order.side(), order.orderType(),
				order.timeInForce(), order.status(), order.price(), order.quantity(), order.orderAmount(),
				order.currency(), order.orderedAt().toString(),
				order.canceledAt() == null ? null : order.canceledAt().toString(),
				new ExecutionResponse(execution.filledQuantity(), execution.averageFilledPrice(), execution.filledAmount(),
						execution.commission(), execution.tax(),
						execution.filledAt() == null ? null : execution.filledAt().toString(),
						execution.settlementDate() == null ? null : execution.settlementDate().toString()));
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record ExecutionResponse(
			String filledQuantity,
			@Schema(nullable = true) @Nullable String averageFilledPrice,
			@Schema(nullable = true) @Nullable String filledAmount,
			@Schema(nullable = true) @Nullable String commission,
			@Schema(nullable = true) @Nullable String tax,
			@Schema(nullable = true) @Nullable String filledAt,
			@Schema(nullable = true) @Nullable String settlementDate) {
	}
}
