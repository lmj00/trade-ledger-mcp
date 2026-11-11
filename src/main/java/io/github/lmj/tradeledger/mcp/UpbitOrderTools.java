package io.github.lmj.tradeledger.mcp;

import java.util.Objects;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.adapter.upbit.UpbitApiException;
import io.github.lmj.tradeledger.adapter.upbit.UpbitOrderClient;
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
			// Lazy bean creation can wrap the client's safe exception. Discard every cause
			// before handing an error to MCP, whose handler selects the innermost message.
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof UpbitApiException safe) {
					throw new IllegalStateException("Upbit request failed: " + safe.code().name());
				}
			}
			throw new IllegalStateException("Upbit request failed: INTERNAL_ERROR");
		}
	}
}
