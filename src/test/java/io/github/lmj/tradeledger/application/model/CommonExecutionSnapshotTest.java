package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Execution;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.ExecutionKey;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Fee;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.FeeScope;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.QueryScope;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Side;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommonExecutionSnapshotTest {
	private static final String ALIAS = " 로컬 Main \uD83D\uDE00 ";
	private static final String SYMBOL = "bTc/원본";
	private static final String PRICE = "12345.1234567890123456789000";
	private static final String QUANTITY = "0.0000000000000000000200";
	private static final String AMOUNT = "0.0000000000000002469024691357800";
	private static final String COMMISSION = "0.0000000000000000000100";
	private static final Instant TIME = OffsetDateTime.parse("2026-07-01T10:00:00.123456789+09:00").toInstant();
	private static final String MARKER = "synthetic-private-marker";

	@ParameterizedTest
	@ValueSource(strings = {"bid", "ask"})
	void mapsUpbitFillsAndOneUnknownCurrencyOrderFee(String side) {
		var source = upbit(side, "done", List.of(upbitFill("fill-1", SYMBOL, side), upbitFill("fill-2", SYMBOL, side)));
		var result = CommonExecutionSnapshot.fromUpbit(ALIAS, source);

		assertThat(result.serviceId()).isEqualTo("upbit");
		assertThat(result.accountAlias()).isEqualTo(ALIAS);
		assertThat(result.symbol()).isEqualTo(SYMBOL);
		assertThat(result.queryScope()).isEqualTo(QueryScope.SINGLE_ORDER);
		assertThat(result.orderId()).contains("order-1");
		assertThat(result.possiblyTruncated()).isEmpty();
		assertThat(result.executions()).containsExactly(
				new Execution("upbit", ALIAS, SYMBOL, "fill-1", "order-1", side.equals("bid") ? Side.BUY : Side.SELL,
						PRICE, QUANTITY, AMOUNT, TIME),
				new Execution("upbit", ALIAS, SYMBOL, "fill-2", "order-1", side.equals("bid") ? Side.BUY : Side.SELL,
						PRICE, QUANTITY, AMOUNT, TIME));
		assertThat(result.executions().getFirst().executedAt().toString()).isEqualTo("2026-07-01T01:00:00.123456789Z");
		assertThat(result.fees()).containsExactly(new Fee(FeeScope.ORDER, "order-1", COMMISSION, Optional.empty()));
		assertThat(source.state()).isEqualTo("done");
		assertThat(source.feeCurrency()).isEmpty();
	}

	@Test
	void preservesCancelledFillsAndDoesNotInventFillsForEmptyOrders() {
		var cancelled = upbit("ask", "cancel", List.of(upbitFill("fill-1", SYMBOL, "ask")));
		assertThat(CommonExecutionSnapshot.fromUpbit(ALIAS, cancelled).executions()).hasSize(1);
		assertThat(cancelled.state()).isEqualTo("cancel");
		for (String state : List.of("wait", "cancel", "done")) {
			var source = new OrderExecutionSnapshot("upbit", "empty-order", SYMBOL, "bid", "limit", state,
					TIME, "0.0000", "0.0000", List.of());
			var result = CommonExecutionSnapshot.fromUpbit(ALIAS, source);
			assertThat(result.executions()).isEmpty();
			assertThat(result.orderId()).contains("empty-order");
			assertThat(result.fees()).containsExactly(new Fee(FeeScope.ORDER, "empty-order", "0.0000", Optional.empty()));
		}
	}

	@Test
	void preservesBinanceSidesExactIdsAmountsAndIndependentFeeAssets() {
		var result = CommonExecutionSnapshot.fromBinance(binance(ALIAS, List.of(
				binanceFill(9_007_199_254_740_993L, Long.MAX_VALUE, true, "bNb"),
				binanceFill(Long.MAX_VALUE, 9_007_199_254_740_993L, false, "USDT")), true));

		assertThat(result.serviceId()).isEqualTo("binance");
		assertThat(result.accountAlias()).isEqualTo(ALIAS);
		assertThat(result.symbol()).isEqualTo(SYMBOL);
		assertThat(result.queryScope()).isEqualTo(QueryScope.RECENT_SYMBOL);
		assertThat(result.orderId()).isEmpty();
		assertThat(result.possiblyTruncated()).contains(true);
		assertThat(result.executions()).containsExactly(
				new Execution("binance", ALIAS, SYMBOL, "9007199254740993", "9223372036854775807", Side.BUY,
						PRICE, QUANTITY, AMOUNT, TIME),
				new Execution("binance", ALIAS, SYMBOL, "9223372036854775807", "9007199254740993", Side.SELL,
						PRICE, QUANTITY, AMOUNT, TIME));
		assertThat(result.fees()).containsExactly(
				new Fee(FeeScope.FILL, "9007199254740993", COMMISSION, Optional.of("bNb")),
				new Fee(FeeScope.FILL, "9223372036854775807", COMMISSION, Optional.of("USDT")));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void preservesTruncationIndependentlyOfEmptyOrShortRecentResults(boolean truncated) {
		for (var fills : List.of(List.<BinanceAccountTradesSnapshot.Execution>of(),
				List.of(binanceFill(1, 2, true, "BNB")))) {
			var result = CommonExecutionSnapshot.fromBinance(binance(ALIAS, fills, truncated));
			assertThat(result.queryScope()).isEqualTo(QueryScope.RECENT_SYMBOL);
			assertThat(result.possiblyTruncated()).contains(truncated);
			assertThat(result.executions()).hasSize(fills.size());
			assertThat(result.fees()).hasSize(fills.size());
			for (QueryScope scope : List.of(QueryScope.RECENT_SYMBOL, QueryScope.FILTERED_SYMBOL)) {
				var scoped = CommonExecutionSnapshot.fromBinance(binance(ALIAS, fills, truncated), scope);
				assertThat(scoped.queryScope()).isEqualTo(scope);
				assertThat(scoped.orderId()).isEmpty();
				assertThat(scoped.possiblyTruncated()).contains(truncated);
				assertThat(scoped.executions()).isEqualTo(result.executions());
				assertThat(scoped.fees()).isEqualTo(result.fees());
			}
		}
	}

	@Test
	void scopedBinanceConversionPreservesExactFillsFeesAndStructuralKeys() {
		var source = binance(ALIAS, List.of(
				binanceFill(9_007_199_254_740_993L, Long.MAX_VALUE, true, "bNb"),
				binanceFill(Long.MAX_VALUE, 9_007_199_254_740_993L, false, "USDT")), true);
		var recent = CommonExecutionSnapshot.fromBinance(source);
		var filtered = CommonExecutionSnapshot.fromBinance(source, QueryScope.FILTERED_SYMBOL);
		assertThat(CommonExecutionSnapshot.fromBinance(source, QueryScope.RECENT_SYMBOL)).isEqualTo(recent);
		assertThat(filtered.queryScope()).isEqualTo(QueryScope.FILTERED_SYMBOL);
		assertThat(filtered.executions()).containsExactlyElementsOf(recent.executions());
		assertThat(filtered.fees()).containsExactlyElementsOf(recent.fees());
		assertThat(filtered.orderId()).isEmpty();
		assertThat(filtered.possiblyTruncated()).contains(true);
		assertThat(filtered.executions().getFirst().key()).isEqualTo(
				new ExecutionKey("binance", ALIAS, SYMBOL, "9007199254740993"));
		assertThat(new ExecutionKey("a|b", "c", SYMBOL, "1"))
				.isNotEqualTo(new ExecutionKey("a", "b|c", SYMBOL, "1"));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, List.of(
				binanceFill(1, 2, true, "BNB"), binanceFill(1, 3, false, MARKER)), false), QueryScope.FILTERED_SYMBOL));
	}

	@Test
	void scopedBinanceConversionRejectsNullAndSingleOrderScopesSafely() {
		var source = binance(ALIAS, List.of(), false);
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(source, null));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(source, QueryScope.SINGLE_ORDER));
		for (QueryScope scope : List.of(QueryScope.RECENT_SYMBOL, QueryScope.FILTERED_SYMBOL)) {
			assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(null, scope));
		}
	}

	@Test
	void definesStructuralIdentityAcrossServicesAccountsAndSymbols() {
		var fills = List.of(binanceFill(1, 2, true, "BNB"));
		var key = CommonExecutionSnapshot.fromBinance(binance(ALIAS, fills, false)).executions().getFirst().key();
		assertThat(key).isEqualTo(new ExecutionKey("binance", ALIAS, SYMBOL, "1"));
		var otherAccount = CommonExecutionSnapshot.fromBinance(binance("other", fills, false));
		var otherService = CommonExecutionSnapshot.fromBinance(
				new BinanceAccountTradesSnapshot("other-service", ALIAS, SYMBOL, fills, false));
		var otherSymbol = CommonExecutionSnapshot.fromBinance(new BinanceAccountTradesSnapshot("binance", ALIAS,
				"other", List.of(new BinanceAccountTradesSnapshot.Execution("other", 1, 2, -1,
						PRICE, QUANTITY, AMOUNT, COMMISSION, "BNB", TIME, true, false, true)), false));
		var upbit = CommonExecutionSnapshot.fromUpbit(ALIAS,
				upbit("bid", "done", List.of(upbitFill("1", SYMBOL, "bid"))));
		assertThat(List.of(key, otherAccount.executions().getFirst().key(), otherService.executions().getFirst().key(),
				otherSymbol.executions().getFirst().key(), upbit.executions().getFirst().key())).doesNotHaveDuplicates();
		assertThat(new ExecutionKey("a|b", "c", SYMBOL, "1"))
				.isNotEqualTo(new ExecutionKey("a", "b|c", SYMBOL, "1"));
	}

	@Test
	void rejectsDuplicateKeysIncludingLaterFillsWithDifferentOrdersOrFees() {
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS, upbit("bid", "done", List.of(
				upbitFill("first", SYMBOL, "bid"), upbitFill(MARKER, SYMBOL, "bid"), upbitFill(MARKER, SYMBOL, "bid")))));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, List.of(
				binanceFill(1, 2, true, "BNB"), binanceFill(1, 3, false, MARKER)), false)));
	}

	@Test
	void rejectsUnknownDirectionsAndParentMarketOrDirectionMismatch() {
		for (String side : List.of("BUY", "Bid", "sell", "", MARKER)) {
			assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS, upbit(side, "done", List.of())));
			assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS,
					upbit("bid", "done", List.of(upbitFill("fill", SYMBOL, side)))));
		}
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS,
				upbit("bid", "done", List.of(upbitFill("fill", SYMBOL, "ask")))));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS,
				upbit("bid", "done", List.of(upbitFill("fill", MARKER, "bid")))));
		var wrongSymbol = new BinanceAccountTradesSnapshot.Execution(MARKER, 1, 2, -1,
				PRICE, QUANTITY, AMOUNT, COMMISSION, "BNB", TIME, true, false, true);
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, List.of(wrongSymbol), false)));
	}

	@Test
	void rejectsNullSnapshotsAndInvalidAliasesWithoutRetainingValuesOrCauses() {
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS, null));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(null));
		for (String alias : new String[] {null, "", " ", "x".repeat(257), "\uD83D\uDE00".repeat(128) + "x",
				MARKER + "\n", "x\u007f", "x\u0085", "x\uD800", "\uDC00x", "\uD800x", "\uD800\uD800"}) {
			assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(alias, upbit("bid", "wait", List.of())));
			if (alias != null) {
				assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(alias, List.of(), false)));
			}
		}
	}

	@Test
	void acceptsAliasBoundaryAndPreservesValidUnicodeWhitespaceAndCase() {
		for (String alias : List.of("x".repeat(256), "\uD83D\uDE00".repeat(128), ALIAS)) {
			var upbit = CommonExecutionSnapshot.fromUpbit(alias,
					upbit("bid", "done", List.of(upbitFill("fill", SYMBOL, "bid"))));
			var binance = CommonExecutionSnapshot.fromBinance(binance(alias,
					List.of(binanceFill(1, 2, true, "BNB")), false));
			assertThat(upbit.accountAlias()).isEqualTo(alias);
			assertThat(upbit.executions().getFirst().key().accountAlias()).isEqualTo(alias);
			assertThat(binance.accountAlias()).isEqualTo(alias);
			assertThat(binance.executions().getFirst().accountAlias()).isEqualTo(alias);
		}
	}

	@Test
	void rejectsMissingOrderMetadataAndAmountsInSyntheticSnapshots() {
		for (String field : List.of("service", "order", "market", "type", "state", "volume", "fee")) {
			var source = new OrderExecutionSnapshot(field.equals("service") ? "" : "upbit",
					field.equals("order") ? "" : "order", field.equals("market") ? "" : SYMBOL, "bid",
					field.equals("type") ? "" : "limit", field.equals("state") ? "" : "done", TIME,
					field.equals("volume") ? "" : QUANTITY, field.equals("fee") ? "" : COMMISSION, List.of());
			assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS, source));
		}
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(
				new BinanceAccountTradesSnapshot("", ALIAS, SYMBOL, List.of(), false)));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(
				new BinanceAccountTradesSnapshot("binance", ALIAS, "", List.of(), false)));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS,
				upbit("bid", "done", List.of(upbitFill("", SYMBOL, "bid")))));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS,
				List.of(binanceFill(1, 2, true, "")), false)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "NaN", "1e3", "-1", " 1", "1 ", "synthetic-private-marker"})
	void rejectsInvalidFillAmountsWithSafeErrors(String value) {
		for (int field = 0; field < 4; field++) {
			var fill = new BinanceAccountTradesSnapshot.Execution(SYMBOL, 1, 2, -1,
					field == 0 ? value : PRICE, field == 1 ? value : QUANTITY,
					field == 2 ? value : AMOUNT, field == 3 ? value : COMMISSION, "BNB", TIME, true, false, true);
			assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, List.of(fill), false)));
		}
	}

	@Test
	void accepts1000AndRejects1001ForBothSources() {
		var upbitFills = IntStream.range(0, 1000).mapToObj(i -> upbitFill("fill-" + i, SYMBOL, "bid"))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		var binanceFills = IntStream.range(0, 1000).mapToObj(i -> binanceFill(i, i, true, "BNB"))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		assertThat(CommonExecutionSnapshot.fromUpbit(ALIAS, upbit("bid", "done", upbitFills)).executions()).hasSize(1000);
		var binance = CommonExecutionSnapshot.fromBinance(binance(ALIAS, binanceFills, false));
		assertThat(binance.executions()).hasSize(1000);
		assertThat(binance.fees()).hasSize(1000);
		assertThat(binance.possiblyTruncated()).contains(false);
		var filtered = CommonExecutionSnapshot.fromBinance(binance(ALIAS, binanceFills, false), QueryScope.FILTERED_SYMBOL);
		assertThat(filtered.executions()).isEqualTo(binance.executions());
		assertThat(filtered.fees()).isEqualTo(binance.fees());
		upbitFills.add(upbitFill("fill-1000", SYMBOL, "bid"));
		binanceFills.add(binanceFill(1000, 1000, true, "BNB"));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromUpbit(ALIAS, upbit("bid", "done", upbitFills)));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, binanceFills, true)));
		assertSafeFailure(() -> CommonExecutionSnapshot.fromBinance(binance(ALIAS, binanceFills, true), QueryScope.FILTERED_SYMBOL));
	}

	@Test
	void copiesInputListsAndExposesImmutableResults() {
		var upbitInput = new ArrayList<>(List.of(upbitFill("fill", SYMBOL, "bid")));
		var binanceInput = new ArrayList<>(List.of(binanceFill(1, 2, true, "BNB")));
		var upbitSource = upbit("bid", "done", upbitInput);
		var binanceSource = binance(ALIAS, binanceInput, false);
		var upbit = CommonExecutionSnapshot.fromUpbit(ALIAS, upbitSource);
		var binance = CommonExecutionSnapshot.fromBinance(binanceSource);
		upbitInput.clear();
		binanceInput.clear();
		assertThat(upbitSource.trades()).hasSize(1);
		assertThat(binanceSource.trades()).hasSize(1);
		for (var snapshot : List.of(upbit, binance)) {
			var fills = new ArrayList<>(snapshot.executions());
			var fees = new ArrayList<>(snapshot.fees());
			var copy = new CommonExecutionSnapshot(snapshot.serviceId(), snapshot.accountAlias(), snapshot.symbol(),
					snapshot.queryScope(), snapshot.orderId(), snapshot.possiblyTruncated(), fills, fees);
			fills.clear();
			fees.clear();
			assertThat(copy).isEqualTo(snapshot);
			assertThatThrownBy(() -> copy.executions().clear()).isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> copy.executions().set(0, snapshot.executions().getFirst()))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> copy.fees().add(snapshot.fees().getFirst()))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Test
	void rejectsMissingCommonFieldsAndInvalidScopeCombinationsSafely() {
		assertSafeFailure(() -> new Execution("binance", ALIAS, SYMBOL, "id", "order", null, PRICE, QUANTITY, AMOUNT, TIME));
		assertSafeFailure(() -> new Execution("binance", ALIAS, SYMBOL, "id", "order", Side.BUY, null, QUANTITY, AMOUNT, TIME));
		assertSafeFailure(() -> new Execution("binance", ALIAS, SYMBOL, "id", "order", Side.BUY, PRICE, QUANTITY, AMOUNT, null));
		assertSafeFailure(() -> new Fee(FeeScope.FILL, "id", COMMISSION, null));
		assertSafeFailure(() -> new CommonExecutionSnapshot("binance", ALIAS, SYMBOL, QueryScope.RECENT_SYMBOL,
				Optional.empty(), Optional.empty(), List.of(), List.of()));
		assertSafeFailure(() -> new CommonExecutionSnapshot("upbit", ALIAS, SYMBOL, QueryScope.SINGLE_ORDER,
				Optional.of("order"), Optional.of(false), List.of(), List.of()));
		assertSafeFailure(() -> new CommonExecutionSnapshot("binance", ALIAS, SYMBOL, QueryScope.RECENT_SYMBOL,
				Optional.empty(), Optional.of(false), null, List.of()));
		var withNull = new ArrayList<Execution>();
		withNull.add(null);
		assertSafeFailure(() -> new CommonExecutionSnapshot("binance", ALIAS, SYMBOL, QueryScope.RECENT_SYMBOL,
				Optional.empty(), Optional.of(false), withNull, List.of()));
	}

	private static OrderExecutionSnapshot upbit(String side, String state, List<OrderExecutionSnapshot.Execution> fills) {
		return new OrderExecutionSnapshot("upbit", "order-1", SYMBOL, side, "limit", state,
				TIME, QUANTITY, COMMISSION, fills);
	}

	private static OrderExecutionSnapshot.Execution upbitFill(String id, String market, String side) {
		return new OrderExecutionSnapshot.Execution(id, market, side, PRICE, QUANTITY, AMOUNT, TIME);
	}

	private static BinanceAccountTradesSnapshot binance(String alias, List<BinanceAccountTradesSnapshot.Execution> fills,
			boolean truncated) {
		return new BinanceAccountTradesSnapshot("binance", alias, SYMBOL, fills, truncated);
	}

	private static BinanceAccountTradesSnapshot.Execution binanceFill(long id, long orderId, boolean buyer, String asset) {
		return new BinanceAccountTradesSnapshot.Execution(SYMBOL, id, orderId, -1, PRICE, QUANTITY,
				AMOUNT, COMMISSION, asset, TIME, buyer, false, true);
	}

	private static void assertSafeFailure(ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid common execution snapshot");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			var trace = new StringWriter();
			exception.printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain(MARKER, ALIAS);
		});
	}
}
