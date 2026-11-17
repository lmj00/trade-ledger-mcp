package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import io.github.lmj.tradeledger.adapter.toss.TossTransactionsMapper;
import io.github.lmj.tradeledger.adapter.toss.TossTransactionsRequest;
import io.github.lmj.tradeledger.application.model.TossTransactionsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TossTransactionsResponseTest {
	static final List<String> NULLABLE = List.of("symbol", "assetClass", "isinCode", "quantity", "filledPrice",
			"amount", "netAmount", "exchangeRate", "transactionTime");
	static final List<String> TYPES = List.of("SELL", "BUY", "BUY", "BUY", "BUY", "BUY", "DEPOSIT",
			"WITHDRAWAL", "EXCHANGE_IN", "EXCHANGE_OUT", "DIVIDEND", "SECURITIES_TRANSFER_IN",
			"SECURITIES_TRANSFER_OUT", " Future_Code 한😀 ");

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 14, 100})
	void projectsAllFieldsNullsEqualRowsAndMetadataWithoutInferringContinuation(int count) {
		String[] rows = IntStream.range(0, count).mapToObj(i -> row(i % TYPES.size())).toArray(String[]::new);
		for (boolean hasNext : List.of(false, true)) {
			String cursor = hasNext ? " next%2F=+/&?#한😀 " : null;
			var snapshot = new TossTransactionsMapper().map(new TossTransactionsRequest(null, null, null, 100),
					envelope(cursor, hasNext, rows));
			var response = TossTransactionsResponse.from(snapshot);
			assertThat(response.serviceId()).isEqualTo("toss");
			assertThat(response.nextCursor()).isEqualTo(cursor);
			assertThat(response.hasNext()).isEqualTo(hasNext);
			assertThat(response.transactions()).hasSize(count);
			assertThatThrownBy(() -> response.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
			tools.jackson.databind.JsonNode actual = TossOrderResponseTest.JSON.valueToTree(response);
			assertThat(actual)
					.isEqualTo(TossOrderResponseTest.JSON.readTree(expectedPage(cursor, hasNext, rows)));
			if (count == 100) {
				assertThat(response.transactions().get(0)).isEqualTo(response.transactions().get(TYPES.size()));
			}
		}
	}

	@Test
	void retainsEveryExplicitNullableFieldAndCopiesCallerOwnedList() {
		var json = TossOrderResponseTest.JSON.readTree(row(0));
		for (String field : NULLABLE) {
			((tools.jackson.databind.node.ObjectNode) json).putNull(field);
		}
		String source = TossOrderResponseTest.JSON.writeValueAsString(json);
		var snapshot = new TossTransactionsMapper().map(new TossTransactionsRequest(), envelope(null, false, source));
		var projected = TossTransactionsResponse.from(snapshot);
		var list = new ArrayList<>(projected.transactions());
		var response = new TossTransactionsResponse("toss", list, null, false);
		list.clear();
		assertThat(response.transactions()).hasSize(1);
		var actual = TossOrderResponseTest.JSON.valueToTree(response).get("transactions").get(0);
		assertThat(actual.size()).isEqualTo(17);
		for (String field : NULLABLE) {
			assertThat(actual.has(field)).isTrue();
			assertThat(actual.get(field).isNull()).isTrue();
		}
		assertThat(actual).isEqualTo(json);
		assertThatNullPointerException().isThrownBy(() -> TossTransactionsResponse.from(null));
		assertThat(snapshot).isEqualTo(new TossTransactionsSnapshot(snapshot.transactions(), null, false));
	}

	// Synthetic domestic unsettled/settled, US stock/option/bond, cash, FX,
	// dividend, securities transfers and unknown-code cancellation rows.
	static String row(int kind) {
		boolean domestic = kind < 2;
		boolean unsettled = kind == 0 || kind == 3;
		boolean cash = kind >= 6 && kind <= 9;
		boolean transfer = kind == 11 || kind == 12;
		String symbol = cash || kind == 5 ? null : domestic ? "005930" : kind == 4 ? "AAPL261218C00150000" : "AAPL";
		String asset = cash ? null : kind == 4 ? "OPTION" : kind == 5 ? "BOND" : kind == 13 ? "FUTURE_ASSET" : "STOCK";
		String isin = cash || kind == 4 ? null : domestic ? "KR7005930003" : kind == 5 ? "US91282CZZ99" : "US0378331005";
		return "{\"transactionType\":" + quote(TYPES.get(kind))
				+ ",\"marketCountry\":" + quote(domestic ? "KR" : kind == 13 ? "FUTURE_MARKET" : "US")
				+ ",\"currency\":" + quote(domestic ? "KRW" : kind == 13 ? "FUTURE_CURRENCY" : "USD")
				+ ",\"symbol\":" + quote(symbol) + ",\"assetClass\":" + quote(asset) + ",\"isinCode\":" + quote(isin)
				+ ",\"quantity\":" + quote(cash ? null : "10.0000")
				+ ",\"filledPrice\":" + quote(cash || unsettled || transfer ? null : "1234567890123456789.1234567890")
				+ ",\"amount\":" + quote(transfer ? null : "710000.00")
				+ ",\"commission\":\"1.2300\",\"tax\":\"4.5600\",\"netAmount\":" + quote(transfer ? null : "709994.2100")
				+ ",\"exchangeRate\":" + quote(domestic || unsettled || transfer ? null : "1386.0000")
				+ ",\"settlementDate\":\"2099-12-31\",\"settled\":" + !unsettled
				+ ",\"transactionTime\":" + quote(unsettled ? null : "2026-09-30T06:30:00.123456789+09:00")
				+ ",\"isCancellation\":" + (kind == 13) + "}";
	}

	static String envelope(String cursor, boolean hasNext, String... rows) {
		return "{\"result\":{\"transactions\":[" + String.join(",", rows) + "],\"nextCursor\":" + quote(cursor)
				+ ",\"hasNext\":" + hasNext + "}}";
	}

	static String expectedPage(String cursor, boolean hasNext, String... rows) {
		return "{\"serviceId\":\"toss\",\"transactions\":["
				+ String.join(",", rows).replace("2026-09-30T06:30:00.123456789+09:00", "2026-09-29T21:30:00.123456789Z")
				+ "],\"nextCursor\":" + quote(cursor) + ",\"hasNext\":" + hasNext + "}";
	}

	private static String quote(String value) {
		return TossOrderResponseTest.JSON.writeValueAsString(value);
	}
}
