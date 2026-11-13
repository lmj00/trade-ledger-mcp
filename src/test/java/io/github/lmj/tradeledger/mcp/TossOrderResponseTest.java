package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.LocalDate;

import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot.ExecutionSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TossOrderResponseTest {
	static final String ID = " PrivateOrder%2F/+?#😀 ";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3})
	void purelyProjectsDomesticForeignUnfilledAndCanceledSummaries(int kind) {
		var response = TossOrderResponse.from(snapshot(kind, ID));
		JsonNode actual = JSON.valueToTree(response);
		assertThat(actual).isEqualTo(JSON.readTree(expected(kind, ID)));
		assertThat(response.serviceId()).isEqualTo("toss");
		assertThat(response.orderId()).isEqualTo(ID);
		assertThat(response.quantity()).isEqualTo("10.00");
		assertThat(response.orderedAt()).isEqualTo("2026-03-28T00:30:00.123456789Z");
		if (kind == 2) {
			var tree = JSON.valueToTree(response);
			for (String field : new String[]{"price", "orderAmount", "canceledAt"}) {
				assertThat(tree.has(field)).isTrue();
				assertThat(tree.get(field).isNull()).isTrue();
			}
			for (String field : new String[]{"averageFilledPrice", "filledAmount", "commission", "tax", "filledAt", "settlementDate"}) {
				assertThat(tree.get("execution").has(field)).isTrue();
				assertThat(tree.get("execution").get(field).isNull()).isTrue();
			}
		}
	}

	@Test
	void preservesLargeExactDecimalsAndSignedCostsWithoutArithmeticOrCodeInference() {
		var source = new TossOrderSnapshot(ID, "US5949181045", "SELL", "future_type", "future_tif", "future_status",
				"9007199254740993.12345678900", "0.00000000000000000200", "99.0000", "future_currency",
				Instant.parse("2026-03-28T00:30:00Z"), null,
				new ExecutionSummary("0.00000000000000000100", "9007199254740993.12345678900",
						"0.009007199254740993123456789", "-0.0000100", "+0.0000200", null, null));
		var response = TossOrderResponse.from(source);
		assertThat(response.price()).isEqualTo("9007199254740993.12345678900");
		assertThat(response.quantity()).isEqualTo("0.00000000000000000200");
		assertThat(response.orderAmount()).isEqualTo("99.0000");
		assertThat(response.orderType()).isEqualTo("future_type");
		assertThat(response.timeInForce()).isEqualTo("future_tif");
		assertThat(response.status()).isEqualTo("future_status");
		assertThat(response.currency()).isEqualTo("future_currency");
		assertThat(response.execution()).isEqualTo(new TossOrderResponse.ExecutionResponse(
				"0.00000000000000000100", "9007199254740993.12345678900", "0.009007199254740993123456789",
				"-0.0000100", "+0.0000200", null, null));
	}

	@Test
	void rejectsNullSnapshot() {
		assertThatNullPointerException().isThrownBy(() -> TossOrderResponse.from(null));
	}

	// Four synthetic source snapshots, with deliberately independent monetary totals.
	static TossOrderSnapshot snapshot(int kind, String id) {
		boolean foreign = kind == 1;
		boolean empty = kind == 2;
		return new TossOrderSnapshot(id, foreign ? "US5949181045" : "005930", "BUY",
				foreign ? "FUTURE_TYPE" : "LIMIT", foreign ? "FUTURE_TIF" : "DAY",
				switch (kind) { case 0 -> "FILLED"; case 1 -> "FUTURE_PARTIAL"; case 2 -> "REJECTED"; default -> "CANCELED"; },
				empty ? null : "70000.00", "10.00", empty ? null : "900.00", foreign ? "USD" : "KRW",
				Instant.parse("2026-03-28T00:30:00.123456789Z"),
				kind == 3 ? Instant.parse("2026-03-28T00:32:00Z") : null,
				new ExecutionSummary(empty ? "0.00" : kind == 0 ? "10.00" : "2.00",
						empty ? null : "69999.5000", empty ? null : "139999.0000", empty ? null : "-1.4000",
						empty ? null : "+0.300", empty ? null : Instant.parse("2026-03-28T00:31:15Z"),
						empty ? null : LocalDate.of(2026, 3, 30)));
	}

	static String expected(int kind, String id) {
		String execution = kind == 2
				? """
				{"filledQuantity":"0.00","averageFilledPrice":null,"filledAmount":null,"commission":null,
				"tax":null,"filledAt":null,"settlementDate":null}
				"""
				: """
				{"filledQuantity":"%s","averageFilledPrice":"69999.5000","filledAmount":"139999.0000",
				"commission":"-1.4000","tax":"+0.300","filledAt":"2026-03-28T00:31:15Z","settlementDate":"2026-03-30"}
				""".formatted(kind == 0 ? "10.00" : "2.00");
		return """
				{"serviceId":"toss","orderId":%s,"symbol":"%s","side":"BUY","orderType":"%s",
				"timeInForce":"%s","status":"%s","price":%s,"quantity":"10.00","orderAmount":%s,
				"currency":"%s","orderedAt":"2026-03-28T00:30:00.123456789Z","canceledAt":%s,"execution":%s}
				""".formatted(JSON.writeValueAsString(id), kind == 1 ? "US5949181045" : "005930",
				kind == 1 ? "FUTURE_TYPE" : "LIMIT", kind == 1 ? "FUTURE_TIF" : "DAY",
				switch (kind) { case 0 -> "FILLED"; case 1 -> "FUTURE_PARTIAL"; case 2 -> "REJECTED"; default -> "CANCELED"; },
				kind == 2 ? "null" : "\"70000.00\"", kind == 2 ? "null" : "\"900.00\"",
				kind == 1 ? "USD" : "KRW", kind == 3 ? "\"2026-03-28T00:32:00Z\"" : "null", execution);
	}

	static String envelope(int kind, String id) {
		// Upstream offset timestamps exercise UTC conversion in the registered path.
		return "{\"result\":" + expected(kind, id).replace("\"serviceId\":\"toss\",", "")
				.replace("2026-03-28T00:30:00.123456789Z", "2026-03-28T09:30:00.123456789+09:00")
				.replace("2026-03-28T00:31:15Z", "2026-03-28T09:31:15+09:00")
				.replace("2026-03-28T00:32:00Z", "2026-03-28T09:32:00+09:00") + "}";
	}
}
