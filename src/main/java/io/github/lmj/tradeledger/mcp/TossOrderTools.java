package io.github.lmj.tradeledger.mcp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import io.github.lmj.tradeledger.adapter.toss.TossApiException;
import io.github.lmj.tradeledger.adapter.toss.TossClosedOrdersRequest;
import io.github.lmj.tradeledger.adapter.toss.TossOrderClient;
import io.github.lmj.tradeledger.adapter.toss.TossOrderRequest;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class TossOrderTools {

	private final ObjectProvider<TossOrderClient> client;

	public TossOrderTools(ObjectProvider<TossOrderClient> client) {
		this.client = Objects.requireNonNull(client, "Toss client provider must not be null");
	}

	@McpTool(
			name = "get_toss_order",
			title = "Get one Toss order summary",
			description = "Read one order and its cumulative execution summary by opaque order ID. "
					+ "Uses server-side credentials. One GET, no retries, individual fills, settlement ledger, "
					+ "order placement, cancellation, history collection or PnL.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public TossOrderResponse getTossOrder(
			@McpToolParam(description = "One opaque order ID, at most 256 UTF-16 units; preserved without trimming or decoding.", required = true)
			String orderId) {
		TossOrderRequest request;
		try {
			request = new TossOrderRequest(orderId);
			// The request model owns text validation; reject malformed UTF-16 before
			// obtaining the lazy client, whose encoder also guards non-MCP callers.
			for (int i = 0; i < orderId.length(); i++) {
				char character = orderId.charAt(i);
				if (Character.isLowSurrogate(character)
						|| (Character.isHighSurrogate(character)
								&& (++i == orderId.length() || !Character.isLowSurrogate(orderId.charAt(i))))) {
					throw new IllegalArgumentException();
				}
			}
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Toss request failed: BAD_REQUEST");
		}
		try {
			return TossOrderResponse.from(client.getObject().getOrder(request));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	@McpTool(
			name = "get_toss_closed_orders",
			title = "Get one page of Toss closed order summaries",
			description = "Read one CLOSED page with independently optional inclusive KST orderedAt date filters. "
					+ "Uses server-side credentials. One GET, no automatic pagination, date splitting, detail queries, "
					+ "individual fills, settlement ledger, common executions, Trade creation, PnL, orders or cancellation.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public TossClosedOrdersResponse getTossClosedOrders(
			@McpToolParam(description = "Optional case-preserving symbol matching [A-Za-z0-9.\\-]+, at most 256 UTF-16 units.", required = false)
			String symbol,
			@McpToolParam(description = "Optional inclusive KST orderedAt start date, strict YYYY-MM-DD; independently usable.", required = false)
			String from,
			@McpToolParam(description = "Optional inclusive KST orderedAt end date, strict YYYY-MM-DD; independently usable, from <= to when both given.", required = false)
			String to,
			@McpToolParam(description = "Optional opaque cursor, at most 256 UTF-16 units; preserved without trimming or decoding.", required = false)
			String cursor,
			@McpToolParam(description = "Maximum orders in this page, 1 through 100; omitted or null defaults to 20.", required = false)
			Integer limit) {
		TossClosedOrdersRequest request;
		try {
			request = new TossClosedOrdersRequest(symbol, from, to, cursor, limit);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Toss request failed: BAD_REQUEST");
		}
		try {
			return TossClosedOrdersResponse.from(client.getObject().getClosedOrders(request));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	private static IllegalStateException safeFailure(RuntimeException exception) {
		// Spring may wrap lazy construction failures containing private configuration.
		// MCP receives a new exception with no original message, cause or suppressed graph.
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof TossApiException safe) {
				return new IllegalStateException("Toss request failed: " + safe.code().name());
			}
		}
		return new IllegalStateException("Toss request failed: INTERNAL_ERROR");
	}
}
