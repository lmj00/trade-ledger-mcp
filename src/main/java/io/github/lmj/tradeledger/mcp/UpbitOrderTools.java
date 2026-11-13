package io.github.lmj.tradeledger.mcp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.adapter.upbit.UpbitApiException;
import io.github.lmj.tradeledger.adapter.upbit.UpbitClosedOrdersRequest;
import io.github.lmj.tradeledger.adapter.upbit.UpbitOrderClient;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class UpbitOrderTools {

	private static final Pattern UUID = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private final ObjectProvider<UpbitOrderClient> client;

	public UpbitOrderTools(ObjectProvider<UpbitOrderClient> client) {
		this.client = Objects.requireNonNull(client, "Upbit client provider must not be null");
	}

	@McpTool(
			name = "get_upbit_order",
			title = "Get one Upbit order",
			description = "Read one Upbit order and its actual fills by UUID. Requires server-side order inquiry credentials. "
					+ "No retries, order placement, cancellation, funds movement, trade matching or realized PnL.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public UpbitOrderResponse getUpbitOrder(
			@McpToolParam(description = "One order UUID in canonical 8-4-4-4-12 hexadecimal form.", required = true)
			String uuid) {
		// Reject input at the tool boundary even when the lazy client cannot start.
		// The client retains its own validation for callers outside MCP.
		if (uuid == null || !UUID.matcher(uuid).matches()) {
			throw new IllegalArgumentException("Upbit request failed: INVALID_UUID");
		}
		try {
			return UpbitOrderResponse.from(client.getObject().getOrder(uuid));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	@McpTool(
			name = "get_upbit_order_executions",
			title = "Get common executions for one Upbit order",
			description = "Read one Upbit order by UUID as common executions and one order-level fee. "
					+ "accountAlias is a non-secret local display label, does not select credentials and is not sent to Upbit. "
					+ "At most 1000 fills, no additional queries, retries, fee currency inference, matching or PnL. "
					+ "A single order does not establish complete account history.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public CommonExecutionResponse getUpbitOrderExecutions(
			@McpToolParam(description = "Required non-secret local display label; does not select credentials or account.", required = true)
			String accountAlias,
			@McpToolParam(description = "One order UUID in canonical 8-4-4-4-12 hexadecimal form.", required = true)
			String uuid) {
		if (uuid == null || !UUID.matcher(uuid).matches()) {
			throw new IllegalArgumentException("Upbit request failed: INVALID_UUID");
		}
		if (!validAlias(accountAlias)) {
			throw new IllegalArgumentException("Upbit request failed: BAD_REQUEST");
		}
		try {
			return CommonExecutionResponse.from(CommonExecutionSnapshot.fromUpbit(
					accountAlias, client.getObject().getOrder(uuid)));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	@McpTool(
			name = "get_upbit_closed_orders",
			title = "Get Upbit closed order summaries",
			description = "Read one bounded result of done/cancel order summaries. Time boundaries are independently optional; "
					+ "at most seven days and 1000 orders. No pagination, retries, fill details, fee currency, "
					+ "trade matching or PnL. Even short or empty results do not establish complete history.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public UpbitClosedOrdersResponse getUpbitClosedOrders(
			@McpToolParam(description = "Optional uppercase market code, for example KRW-BTC.", required = false)
			String market,
			@McpToolParam(description = "done or cancel; mutually exclusive with states.", required = false)
			String state,
			@McpToolParam(description = "Nonempty distinct done/cancel states; defaults to both.", required = false)
			List<String> states,
			@McpToolParam(description = "Optional offset ISO8601 or epoch milliseconds; independently usable. Alone, Upbit queries the following seven days.", required = false)
			String startTime,
			@McpToolParam(description = "Optional offset ISO8601 or epoch milliseconds; independently usable. Alone, Upbit queries the preceding seven days. With startTime, ordered within seven days.", required = false)
			String endTime,
			@McpToolParam(description = "Maximum orders, 1 through 1000; defaults to 100.", required = false)
			Integer limit,
			@McpToolParam(description = "asc or desc; defaults to desc.", required = false)
			String orderBy) {
		UpbitClosedOrdersRequest request;
		try {
			request = new UpbitClosedOrdersRequest(market, state, states, startTime, endTime, limit, orderBy);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Upbit request failed: BAD_REQUEST");
		}
		try {
			return UpbitClosedOrdersResponse.from(client.getObject().getClosedOrders(request));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	// Match the Binance tool and common model before lazy client/credential access.
	// Keep this boundary guard even though the pure common converter validates too.
	private static boolean validAlias(String value) {
		if (value == null || value.length() > 256 || value.isBlank()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (Character.isISOControl(character) || Character.isLowSurrogate(character)) {
				return false;
			}
			if (Character.isHighSurrogate(character)
					&& (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) {
				return false;
			}
		}
		return true;
	}

	private static IllegalStateException safeFailure(RuntimeException exception) {
		// Lazy bean creation can wrap the client's safe exception. Discard every cause
		// before handing an error to MCP, whose handler selects the innermost message.
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof UpbitApiException safe) {
				return new IllegalStateException("Upbit request failed: " + safe.code().name());
			}
		}
		return new IllegalStateException("Upbit request failed: INTERNAL_ERROR");
	}
}
