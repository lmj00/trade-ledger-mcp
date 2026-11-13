package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Pure JSON projection: absent model Optionals become explicit null values. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommonExecutionResponse(
		String serviceId,
		String accountAlias,
		String symbol,
		String queryScope,
		@Schema(nullable = true, description = "Order ID, or null for a recent-symbol or filtered-symbol query.")
		@Nullable String orderId,
		@Schema(nullable = true, description = "Truncation hint, or null when inapplicable to a single order.")
		@Nullable Boolean possiblyTruncated,
		List<ExecutionResponse> executions,
		List<FeeResponse> fees) {

	public CommonExecutionResponse {
		executions = List.copyOf(executions);
		fees = List.copyOf(fees);
	}

	public static CommonExecutionResponse from(CommonExecutionSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new CommonExecutionResponse(snapshot.serviceId(), snapshot.accountAlias(), snapshot.symbol(),
				snapshot.queryScope().name(), snapshot.orderId().orElse(null), snapshot.possiblyTruncated().orElse(null),
				snapshot.executions().stream().map(execution -> new ExecutionResponse(
						execution.serviceId(), execution.accountAlias(), execution.symbol(), execution.executionId(),
						execution.orderId(), execution.side().name(), execution.price(), execution.quantity(),
						execution.amount(), execution.executedAt().toString())).toList(),
				snapshot.fees().stream().map(fee -> new FeeResponse(fee.scope().name(), fee.targetId(),
						fee.amount(), fee.currency().orElse(null))).toList());
	}

	public record ExecutionResponse(String serviceId, String accountAlias, String symbol, String executionId,
			String orderId, String side, String price, String quantity, String amount, String executedAt) {
	}

	/** Fee ownership and unknown currency are retained without allocation or inference. */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record FeeResponse(String scope, String targetId, String amount,
			@Schema(nullable = true, description = "Source fee currency, or null when unknown.")
			@Nullable String currency) {
	}
}
