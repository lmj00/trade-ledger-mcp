package io.github.lmj.tradeledger.mcp;

import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.adapter.upbit.UpbitApiException;
import io.github.lmj.tradeledger.adapter.upbit.UpbitClosedOrdersRequest;
import io.github.lmj.tradeledger.adapter.upbit.UpbitErrorCode;
import io.github.lmj.tradeledger.adapter.upbit.UpbitOrderClient;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public final class UpbitOrderTools {

	private static final Pattern UUID = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private final ObjectProvider<UpbitOrderClient> client;

	public UpbitOrderTools(ObjectProvider<UpbitOrderClient> client) {
		this.client = Objects.requireNonNull(client, "Upbit client provider must not be null");
	}

	@Bean
	static BeanPostProcessor upbitToolErrorBoundary() {
		// Run after tool registration, without instantiating the lazy client. Keep the
		// generated tool definitions (including both schemas) and successful results.
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (!"toolSpecs".equals(beanName) || !(bean instanceof List<?> specifications)) {
					return bean;
				}
				return specifications.stream().map(entry -> {
					if (!(entry instanceof SyncToolSpecification specification)) {
						return entry;
					}
					String method = switch (specification.tool().name()) {
						case "get_upbit_order" -> "getUpbitOrder";
						case "get_upbit_order_executions" -> "getUpbitOrderExecutions";
						case "get_upbit_closed_orders" -> "getUpbitClosedOrders";
						default -> null;
					};
					if (method == null) {
						return entry;
					}
					return new SyncToolSpecification(specification.tool(), (exchange, request) -> {
						UpbitErrorCode invalid = rawArgumentError(method, request.arguments());
						if (invalid != null) {
							// Do not let the annotation adapter coerce input or log conversion diagnostics.
							return CallToolResult.builder().isError(true)
									.addTextContent("Upbit request failed: " + invalid.name()).build();
						}
						if (request.arguments() == null) {
							// Only the all-optional closed-orders tool can reach this default query.
							request = CallToolRequest.builder(request.name()).arguments(Map.of())
									.meta(request.meta()).build();
						}
						return normalizeError(specification.callHandler().apply(exchange, request), method);
					});
				}).toList();
			}
		};
	}

	private static UpbitErrorCode rawArgumentError(String method, Map<String, Object> arguments) {
		Map<String, Object> values = arguments == null ? Map.of() : arguments;
		if (!"getUpbitClosedOrders".equals(method)) {
			// Preserve UUID precedence even if accountAlias would fail adapter conversion.
			if (!(values.get("uuid") instanceof String uuid) || !UUID.matcher(uuid).matches()) {
				return UpbitErrorCode.INVALID_UUID;
			}
			if ("getUpbitOrderExecutions".equals(method)
					&& !(values.get("accountAlias") instanceof String)) {
				return UpbitErrorCode.BAD_REQUEST;
			}
			return null;
		}
		for (String field : List.of("market", "state", "startTime", "endTime", "orderBy")) {
			Object value = values.get(field);
			if (value != null && !(value instanceof String)) {
				return UpbitErrorCode.BAD_REQUEST;
			}
		}
		Object states = values.get("states");
		if (states != null) {
			// Bound the collection before inspecting elements; never coerce them to text.
			if (!(states instanceof List<?> list) || list.size() > 2) {
				return UpbitErrorCode.BAD_REQUEST;
			}
			for (Object state : list) {
				if (!(state instanceof String)) {
					return UpbitErrorCode.BAD_REQUEST;
				}
			}
		}
		Object limit = values.get("limit");
		if (limit != null) {
			// Project policy: reject floating-point/decimal representations, including 1.0.
			// Check before the adapter narrows to Integer, so large integers cannot wrap.
			if (limit instanceof BigInteger integer) {
				if (integer.compareTo(BigInteger.ONE) < 0 || integer.compareTo(BigInteger.valueOf(1000)) > 0) {
					return UpbitErrorCode.BAD_REQUEST;
				}
			}
			else if (limit instanceof Byte || limit instanceof Short || limit instanceof Integer || limit instanceof Long) {
				long integer = ((Number) limit).longValue();
				if (integer < 1 || integer > 1000) {
					return UpbitErrorCode.BAD_REQUEST;
				}
			}
			else {
				return UpbitErrorCode.BAD_REQUEST;
			}
		}
		return null;
	}

	private static CallToolResult normalizeError(CallToolResult result, String method) {
		if (!Boolean.TRUE.equals(result.isError())) {
			return result;
		}
		String message = "Upbit request failed: INTERNAL_ERROR";
		if (result.content().size() == 1 && result.content().getFirst() instanceof TextContent text) {
			// Match complete known messages, never forward arbitrary framework diagnostics.
			for (UpbitErrorCode code : UpbitErrorCode.values()) {
				String safe = "Upbit request failed: " + code.name();
				if (text.text().equals(safe)
						|| text.text().equals("Error invoking method: " + method + "\n" + safe)) {
					message = safe;
					break;
				}
			}
		}
		return CallToolResult.builder().isError(true).addTextContent(message).build();
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
		// before MCP adds its invocation prefix; the registration boundary removes it.
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof UpbitApiException safe) {
				return new IllegalStateException("Upbit request failed: " + safe.code().name());
			}
		}
		return new IllegalStateException("Upbit request failed: INTERNAL_ERROR");
	}
}
