package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lmj.tradeledger.application.model.TossClosedOrdersSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Pure projection of one page; pagination metadata is never inferred from its size. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TossClosedOrdersResponse(
		String serviceId,
		List<TossOrderResponse> orders,
		@Schema(nullable = true) @Nullable String nextCursor,
		boolean hasNext) {

	public TossClosedOrdersResponse {
		orders = List.copyOf(orders);
	}

	public static TossClosedOrdersResponse from(TossClosedOrdersSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new TossClosedOrdersResponse("toss", snapshot.orders().stream()
				.map(TossOrderResponse::from).toList(), snapshot.nextCursor(), snapshot.hasNext());
	}
}
