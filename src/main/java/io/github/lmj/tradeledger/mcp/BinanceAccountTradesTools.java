package io.github.lmj.tradeledger.mcp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import io.github.lmj.tradeledger.adapter.binance.BinanceAccountTradesClient;
import io.github.lmj.tradeledger.adapter.binance.BinanceAccountTradesRequest;
import io.github.lmj.tradeledger.adapter.binance.BinanceApiException;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class BinanceAccountTradesTools {

	private final ObjectProvider<BinanceAccountTradesClient> client;

	public BinanceAccountTradesTools(ObjectProvider<BinanceAccountTradesClient> client) {
		this.client = Objects.requireNonNull(client, "Binance client provider must not be null");
	}

	@McpTool(
			name = "get_binance_account_trades",
			title = "Get Binance Spot account trades for one symbol",
			description = "Read one result of Spot fills for one symbol, default 500 and maximum 1000. "
					+ "Optional fromId is an inclusive trade ID lower bound. Optional startTime/endTime are inclusive "
					+ "millisecond bounds (at most 24 hours apart), incompatible with fromId. Omitted filters mean recent fills. "
					+ "accountAlias is a non-secret local display label and does not select the authenticated account. "
					+ "Even short or empty results do not establish complete history. "
					+ "No pagination, retries, fee conversion, trade matching, PnL, orders or funds movement.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public BinanceAccountTradesResponse getBinanceAccountTrades(
			@McpToolParam(description = "Required non-secret local display label; does not select credentials or account.", required = true)
			String accountAlias,
			@McpToolParam(description = "Required single Spot symbol, for example BTCUSDT.", required = true)
			String symbol,
			@McpToolParam(description = "Maximum fills, 1 through 1000; defaults to 500.", required = false)
			Integer limit,
			@McpToolParam(description = "Optional inclusive trade ID lower bound: canonical decimal string from 0 through 9223372036854775807, without signs, spaces or leading zeroes.", required = false)
			String fromId,
			@McpToolParam(description = "Optional inclusive start: project policy requires canonical ASCII nonnegative int64 decimal string in milliseconds only; cannot mix with fromId.", required = false)
			String startTime,
			@McpToolParam(description = "Optional inclusive end: same project millisecond string policy as startTime; start <= end and span <= 86400000ms when both supplied.", required = false)
			String endTime) {
		BinanceAccountTradesRequest request = validatedRequest(accountAlias, symbol, limit, fromId, startTime, endTime);
		try {
			return BinanceAccountTradesResponse.from(client.getObject().getAccountTrades(accountAlias, request));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	/** Preserve existing Java callers; only the six-argument method is registered. */
	public BinanceAccountTradesResponse getBinanceAccountTrades(String accountAlias, String symbol, Integer limit, String fromId) {
		return getBinanceAccountTrades(accountAlias, symbol, limit, fromId, null, null);
	}

	public BinanceAccountTradesResponse getBinanceAccountTrades(String accountAlias, String symbol, Integer limit) {
		return getBinanceAccountTrades(accountAlias, symbol, limit, null);
	}

	@McpTool(
			name = "get_binance_account_executions",
			title = "Get common executions for recent Binance Spot account trades",
			description = "Read one recent-symbol Spot result as common executions and per-fill fees, default 500 and maximum 1000. "
					+ "accountAlias is a non-secret local display label, does not select credentials and is not sent to Binance. "
					+ "Even short or empty results do not establish complete history. "
					+ "No additional queries, pagination, retries, fee aggregation or conversion, matching, PnL, orders or funds movement.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true,
					destructiveHint = false,
					idempotentHint = true,
					openWorldHint = true))
	public CommonExecutionResponse getBinanceAccountExecutions(
			@McpToolParam(description = "Required non-secret local display label; does not select credentials or account.", required = true)
			String accountAlias,
			@McpToolParam(description = "Required single Spot symbol, for example BTCUSDT.", required = true)
			String symbol,
			@McpToolParam(description = "Maximum fills, 1 through 1000; defaults to 500.", required = false)
			Integer limit) {
		BinanceAccountTradesRequest request = validatedRequest(accountAlias, symbol, limit, null, null, null);
		try {
			return CommonExecutionResponse.from(CommonExecutionSnapshot.fromBinance(
					client.getObject().getAccountTrades(accountAlias, request)));
		}
		catch (RuntimeException exception) {
			throw safeFailure(exception);
		}
	}

	private static BinanceAccountTradesRequest validatedRequest(String accountAlias, String symbol, Integer limit, String fromId, String startTime, String endTime) {
		try {
			// Match the client's alias policy before obtaining the lazy client. The
			// request model owns symbol/limit/fromId/time validation for every MCP call too.
			if (!validAlias(accountAlias)) {
				throw new IllegalArgumentException();
			}
			return new BinanceAccountTradesRequest(symbol, limit == null ? 500 : limit, fromId, startTime, endTime);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Binance request failed: BAD_REQUEST");
		}
	}

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
		// Lazy initialization can wrap a safe client exception in a bean exception.
		// Discard wrapper messages, causes and suppressed exceptions before MCP logs it.
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof BinanceApiException safe) {
				return new IllegalStateException("Binance request failed: " + safe.code().name());
			}
		}
		return new IllegalStateException("Binance request failed: INTERNAL_ERROR");
	}
}
