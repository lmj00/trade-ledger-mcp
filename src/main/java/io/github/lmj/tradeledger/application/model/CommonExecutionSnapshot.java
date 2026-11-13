package io.github.lmj.tradeledger.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure internal projection of supplied fills, independent of completed Trade and PnL.
 * Strings preserve source identifiers, decimal precision and scale. Neither query
 * scope nor possiblyTruncated establishes complete account history.
 */
public record CommonExecutionSnapshot(
		String serviceId,
		String accountAlias,
		String symbol,
		QueryScope queryScope,
		Optional<String> orderId,
		Optional<Boolean> possiblyTruncated,
		List<Execution> executions,
		List<Fee> fees) {

	private static final int MAX_EXECUTIONS = 1_000;
	private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");

	public CommonExecutionSnapshot {
		requireText(serviceId);
		requireAlias(accountAlias);
		requireText(symbol);
		require(queryScope != null && orderId != null && possiblyTruncated != null);
		orderId.ifPresent(CommonExecutionSnapshot::requireText);
		require(queryScope == QueryScope.SINGLE_ORDER
				? orderId.isPresent() && possiblyTruncated.isEmpty()
				: orderId.isEmpty() && possiblyTruncated.isPresent());
		executions = immutable(executions);
		fees = immutable(fees);
		var keys = new HashSet<ExecutionKey>();
		for (Execution execution : executions) {
			require(serviceId.equals(execution.serviceId()) && accountAlias.equals(execution.accountAlias())
					&& symbol.equals(execution.symbol()) && keys.add(execution.key()));
			require(orderId.isEmpty() || orderId.get().equals(execution.orderId()));
		}
	}

	/** paidFee is recorded once, including for an order without fills. */
	public static CommonExecutionSnapshot fromUpbit(String accountAlias, OrderExecutionSnapshot snapshot) {
		requireAlias(accountAlias);
		require(snapshot != null);
		requireText(snapshot.serviceId());
		requireText(snapshot.orderId());
		requireText(snapshot.market());
		requireText(snapshot.orderType());
		requireText(snapshot.state());
		require(snapshot.createdAt() != null);
		requireDecimal(snapshot.executedVolume());
		Side side = upbitSide(snapshot.side());
		requireSize(snapshot.trades());
		var executions = new ArrayList<Execution>(snapshot.trades().size());
		for (OrderExecutionSnapshot.Execution trade : snapshot.trades()) {
			require(trade != null);
			require(snapshot.market().equals(trade.market()) && side == upbitSide(trade.side()));
			executions.add(new Execution(snapshot.serviceId(), accountAlias, trade.market(), trade.tradeId(),
					snapshot.orderId(), side, trade.price(), trade.volume(), trade.funds(), trade.createdAt()));
		}
		return new CommonExecutionSnapshot(snapshot.serviceId(), accountAlias, snapshot.market(),
				QueryScope.SINGLE_ORDER, Optional.of(snapshot.orderId()), Optional.empty(), executions,
				List.of(new Fee(FeeScope.ORDER, snapshot.orderId(), snapshot.paidFee(), Optional.empty())));
	}

	/** Commission assets are retained verbatim per fill, without aggregation or conversion. */
	public static CommonExecutionSnapshot fromBinance(BinanceAccountTradesSnapshot snapshot) {
		return fromBinance(snapshot, QueryScope.RECENT_SYMBOL);
	}

	/** Scope describes the supplied result; no request or additional query is needed. */
	public static CommonExecutionSnapshot fromBinance(BinanceAccountTradesSnapshot snapshot, QueryScope scope) {
		require(scope == QueryScope.RECENT_SYMBOL || scope == QueryScope.FILTERED_SYMBOL);
		require(snapshot != null);
		requireText(snapshot.serviceId());
		requireAlias(snapshot.accountAlias());
		requireText(snapshot.symbol());
		requireSize(snapshot.trades());
		var executions = new ArrayList<Execution>(snapshot.trades().size());
		var fees = new ArrayList<Fee>(snapshot.trades().size());
		for (BinanceAccountTradesSnapshot.Execution trade : snapshot.trades()) {
			require(trade != null && snapshot.symbol().equals(trade.symbol()));
			String executionId = Long.toString(trade.id());
			executions.add(new Execution(snapshot.serviceId(), snapshot.accountAlias(), trade.symbol(), executionId,
					Long.toString(trade.orderId()), trade.isBuyer() ? Side.BUY : Side.SELL,
					trade.price(), trade.qty(), trade.quoteQty(), trade.time()));
			requireText(trade.commissionAsset());
			fees.add(new Fee(FeeScope.FILL, executionId, trade.commission(), Optional.of(trade.commissionAsset())));
		}
		return new CommonExecutionSnapshot(snapshot.serviceId(), snapshot.accountAlias(), snapshot.symbol(),
				scope, Optional.empty(), Optional.of(snapshot.possiblyTruncated()), executions, fees);
	}

	public enum QueryScope { SINGLE_ORDER, RECENT_SYMBOL, FILTERED_SYMBOL }

	public enum Side { BUY, SELL }

	public enum FeeScope { ORDER, FILL }

	/** Structural identity: no delimiter encoding and no global uniqueness assumption. */
	public record ExecutionKey(String serviceId, String accountAlias, String symbol, String executionId) {
		public ExecutionKey {
			requireText(serviceId);
			requireAlias(accountAlias);
			requireText(symbol);
			requireText(executionId);
		}
	}

	public record Execution(String serviceId, String accountAlias, String symbol, String executionId,
			String orderId, Side side, String price, String quantity, String amount, Instant executedAt) {
		public Execution {
			requireText(serviceId);
			requireAlias(accountAlias);
			requireText(symbol);
			requireText(executionId);
			requireText(orderId);
			require(side != null && executedAt != null);
			requireDecimal(price);
			requireDecimal(quantity);
			requireDecimal(amount);
		}

		public ExecutionKey key() {
			return new ExecutionKey(serviceId, accountAlias, symbol, executionId);
		}
	}

	/** An empty currency is unknown, never inferred from the market or symbol. */
	public record Fee(FeeScope scope, String targetId, String amount, Optional<String> currency) {
		public Fee {
			require(scope != null && currency != null);
			requireText(targetId);
			requireDecimal(amount);
			currency.ifPresent(CommonExecutionSnapshot::requireText);
		}
	}

	private static Side upbitSide(String side) {
		if ("bid".equals(side)) {
			return Side.BUY;
		}
		if ("ask".equals(side)) {
			return Side.SELL;
		}
		throw invalid();
	}

	private static void requireText(String value) {
		require(value != null && !value.isBlank() && value.length() <= 256);
	}

	// Match the Binance tool's UTF-16 alias policy, without trimming or normalization.
	private static void requireAlias(String value) {
		requireText(value);
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			require(!Character.isISOControl(character) && !Character.isLowSurrogate(character));
			if (Character.isHighSurrogate(character)) {
				require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)));
			}
		}
	}

	private static void requireDecimal(String value) {
		require(value != null && value.length() <= 128 && DECIMAL.matcher(value).matches());
		require(new BigDecimal(value).signum() >= 0);
	}

	private static void requireSize(List<?> values) {
		require(values != null && values.size() <= MAX_EXECUTIONS);
	}

	private static <T> List<T> immutable(List<T> values) {
		requireSize(values);
		try {
			return List.copyOf(values);
		}
		catch (NullPointerException exception) {
			throw invalid();
		}
	}

	private static void require(boolean valid) {
		if (!valid) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid common execution snapshot");
	}
}
