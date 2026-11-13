package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Execution;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Fee;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.FeeScope;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.QueryScope;
import io.github.lmj.tradeledger.application.model.CommonExecutionSnapshot.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CommonExecutionResponseTest {
	private static final String ALIAS = " 로컬 Main \uD83D\uDE00 ";
	private static final Instant TIME = Instant.parse("2026-07-01T01:00:00.123456789Z");

	@Test
	void projectsEveryCommonFieldAndSerializesAbsentOptionalsAsExplicitNulls() {
		var snapshot = new CommonExecutionSnapshot("upbit", ALIAS, "KRW-BTC", QueryScope.SINGLE_ORDER,
				Optional.of("order-1"), Optional.empty(), List.of(execution("fill-1", Side.BUY)),
				List.of(new Fee(FeeScope.ORDER, "order-1", "0.0000100", Optional.empty())));
		var result = CommonExecutionResponse.from(snapshot);
		assertThat(result.serviceId()).isEqualTo("upbit");
		assertThat(result.accountAlias()).isEqualTo(ALIAS);
		assertThat(result.symbol()).isEqualTo("KRW-BTC");
		assertThat(result.queryScope()).isEqualTo("SINGLE_ORDER");
		assertThat(result.orderId()).isEqualTo("order-1");
		assertThat(result.possiblyTruncated()).isNull();
		assertThat(result.executions()).containsExactly(new CommonExecutionResponse.ExecutionResponse(
				"upbit", ALIAS, "KRW-BTC", "fill-1", "order-1", "BUY", "9007199254740993.12345678900",
				"0.00000000000000000200", "0.01801439850948198624691357800", TIME.toString()));
		assertThat(result.fees()).containsExactly(new CommonExecutionResponse.FeeResponse(
				"ORDER", "order-1", "0.0000100", null));
		var json = JsonMapper.builder().build().valueToTree(result);
		assertThat(json.has("possiblyTruncated")).isTrue();
		assertThat(json.get("possiblyTruncated").isNull()).isTrue();
		assertThat(json.get("fees").get(0).has("currency")).isTrue();
		assertThat(json.get("fees").get(0).get("currency").isNull()).isTrue();
		assertThat(json.get("orderId").asString()).isEqualTo("order-1");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void projectsPresentTruncationAndFeeCurrencyWithoutExchangeSpecificMapping(boolean truncated) {
		var snapshot = new CommonExecutionSnapshot("upbit", ALIAS, "KRW-BTC", QueryScope.RECENT_SYMBOL,
				Optional.empty(), Optional.of(truncated), List.of(execution("fill-1", Side.SELL)),
				List.of(new Fee(FeeScope.FILL, "fill-1", "0.0000100", Optional.of("bNb"))));
		var result = CommonExecutionResponse.from(snapshot);
		assertThat(result.queryScope()).isEqualTo("RECENT_SYMBOL");
		assertThat(result.orderId()).isNull();
		assertThat(result.possiblyTruncated()).isEqualTo(truncated);
		assertThat(result.executions().getFirst().side()).isEqualTo("SELL");
		assertThat(result.fees()).containsExactly(new CommonExecutionResponse.FeeResponse(
				"FILL", "fill-1", "0.0000100", "bNb"));
		var json = JsonMapper.builder().build().valueToTree(result);
		assertThat(json.has("orderId")).isTrue();
		assertThat(json.get("orderId").isNull()).isTrue();
		assertThat(json.get("possiblyTruncated").asBoolean()).isEqualTo(truncated);
	}

	@Test
	void preservesEmptyAndMaximumSizedCommonSnapshotsAndDefensivelyCopiesDtoLists() {
		for (int size : List.of(0, 1000)) {
			var snapshot = new CommonExecutionSnapshot("upbit", ALIAS, "KRW-BTC", QueryScope.SINGLE_ORDER,
					Optional.of("order-1"), Optional.empty(),
					IntStream.range(0, size).mapToObj(i -> execution("fill-" + i, Side.BUY)).toList(),
					List.of(new Fee(FeeScope.ORDER, "order-1", "0.0000", Optional.empty())));
			var result = CommonExecutionResponse.from(snapshot);
			var executions = new ArrayList<>(result.executions());
			var fees = new ArrayList<>(result.fees());
			var copy = new CommonExecutionResponse(result.serviceId(), result.accountAlias(), result.symbol(),
					result.queryScope(), result.orderId(), result.possiblyTruncated(), executions, fees);
			executions.clear();
			fees.clear();
			assertThat(copy).isEqualTo(result);
			assertThat(copy.executions()).hasSize(size);
			assertThat(copy.fees()).hasSize(1);
			assertThatThrownBy(() -> copy.executions().add(null)).isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> copy.fees().clear()).isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void bothBinanceScopesSerializeNullOrderAndOriginalTruncationEvenWhenEmpty(boolean truncated) {
		for (QueryScope scope : List.of(QueryScope.RECENT_SYMBOL, QueryScope.FILTERED_SYMBOL)) {
			for (boolean empty : List.of(false, true)) {
				var fill = new Execution("binance", ALIAS, "BTCUSDT", "9007199254740993", "9223372036854775807",
						Side.BUY, "1.0000", "0.0200", "0.02000000", TIME);
				var snapshot = new CommonExecutionSnapshot("binance", ALIAS, "BTCUSDT", scope,
						Optional.empty(), Optional.of(truncated), empty ? List.of() : List.of(fill),
						empty ? List.of() : List.of(new Fee(FeeScope.FILL, "9007199254740993", "0.0000100", Optional.of("BNB"))));
				var result = CommonExecutionResponse.from(snapshot);
				assertThat(result.queryScope()).isEqualTo(scope.name());
				assertThat(result.orderId()).isNull();
				assertThat(result.possiblyTruncated()).isEqualTo(truncated);
				assertThat(result.executions()).hasSize(empty ? 0 : 1);
				assertThat(result.fees()).hasSize(empty ? 0 : 1);
				var json = JsonMapper.builder().build().valueToTree(result);
				assertThat(json.has("orderId")).isTrue();
				assertThat(json.get("orderId").isNull()).isTrue();
				assertThat(json.get("possiblyTruncated").asBoolean()).isEqualTo(truncated);
			}
		}
	}

	@Test
	void rejectsNullSnapshot() {
		assertThatNullPointerException().isThrownBy(() -> CommonExecutionResponse.from(null));
	}

	private static Execution execution(String id, Side side) {
		return new Execution("upbit", ALIAS, "KRW-BTC", id, "order-1", side,
				"9007199254740993.12345678900", "0.00000000000000000200",
				"0.01801439850948198624691357800", TIME);
	}
}
