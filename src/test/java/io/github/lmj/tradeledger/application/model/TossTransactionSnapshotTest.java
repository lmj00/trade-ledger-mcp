package io.github.lmj.tradeledger.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

class TossTransactionSnapshotTest {

	private static final List<String> TEXT = List.of("transactionType", "marketCountry", "currency",
			"symbol", "assetClass", "isinCode");
	private static final List<String> DECIMALS = List.of("quantity", "filledPrice", "amount", "commission",
			"tax", "netAmount", "exchangeRate");

	@Test
	void retainsAllSeventeenFieldsWithoutInferringSettlementOrRecalculatingMoney() {
		var row = row(null, null);
		assertThat(Arrays.stream(TossTransactionSnapshot.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("transactionType", "marketCountry", "currency", "symbol", "assetClass", "isinCode",
						"quantity", "filledPrice", "amount", "commission", "tax", "netAmount", "exchangeRate",
						"settlementDate", "settled", "transactionTime", "isCancellation");
		assertThat(row.quantity()).isEqualTo("1.0000");
		assertThat(row.filledPrice()).isEqualTo("1.0000");
		assertThat(row.amount()).isEqualTo("1.0000");
		assertThat(row.commission()).isEqualTo("1.0000");
		assertThat(row.tax()).isEqualTo("1.0000");
		assertThat(row.netAmount()).isEqualTo("1.0000");
		assertThat(row.exchangeRate()).isEqualTo("1.0000");
		assertThat(row.settlementDate()).isEqualTo(LocalDate.of(2000, 1, 1));
		assertThat(row.settled()).isFalse();
		assertThat(row.isCancellation()).isTrue();
		assertThat(row.transactionTime()).isEqualTo(Instant.EPOCH);
	}

	@Test
	void validatesTextWithoutRestrictingUnknownCodesOrNormalizingValues() {
		for (String field : TEXT) {
			for (String value : List.of(" Future_Code 한😀 ", "x".repeat(256), "😀".repeat(128))) {
				assertThat(value(row(field, value), field)).isEqualTo(value);
			}
			for (String value : List.of("", " ", "\u2003", "x\n", "x\u007f", "x".repeat(257),
					"😀".repeat(128) + "x", "\ud800", "\udc00", "\ud800x")) {
				reject(() -> row(field, value));
			}
			if (List.of("symbol", "assetClass", "isinCode").contains(field)) {
				assertThat(value(row(field, null), field)).isNull();
			}
			else {
				reject(() -> row(field, null));
			}
		}
	}

	@Test
	void checksEveryDecimalAsUnmodifiedNonnegativeThirtyCharacterText() {
		for (String field : DECIMALS) {
			for (String value : List.of("0", "000.0100", "1".repeat(30), "0." + "1".repeat(26) + "00")) {
				assertThat(value(row(field, value), field)).isEqualTo(value);
			}
			for (String value : List.of("1".repeat(31), "0." + "1".repeat(29), "+1", "-0", "-1.0", "1e2",
					"1E-2", "NaN", "Infinity", "-Infinity", ".5", "1.", " 1", "1 ", "1,000", "", "한")) {
				reject(() -> row(field, value));
			}
			if (List.of("commission", "tax").contains(field)) {
				reject(() -> row(field, null));
			}
			else {
				assertThat(value(row(field, null), field)).isNull();
			}
		}
		reject(() -> new TossTransactionSnapshot("BUY", "KR", "KRW", null, null, null, null, null,
				null, "0", "0", null, null, null, false, null, false));
		assertThat(new TossTransactionSnapshot("Future", "Future", "Future", null, null, null, null, null,
				null, "0", "0", null, null, LocalDate.of(2099, 1, 1), true, null, false).settled()).isTrue();
	}

	private static TossTransactionSnapshot row(String field, String value) {
		var fields = new HashMap<String, String>();
		TEXT.forEach(f -> fields.put(f, "Future_Code"));
		DECIMALS.forEach(f -> fields.put(f, "1.0000"));
		if (field != null) {
			fields.put(field, value);
		}
		return new TossTransactionSnapshot(fields.get("transactionType"), fields.get("marketCountry"),
				fields.get("currency"), fields.get("symbol"), fields.get("assetClass"), fields.get("isinCode"),
				fields.get("quantity"), fields.get("filledPrice"), fields.get("amount"), fields.get("commission"),
				fields.get("tax"), fields.get("netAmount"), fields.get("exchangeRate"), LocalDate.of(2000, 1, 1),
				false, Instant.EPOCH, true);
	}

	private static Object value(TossTransactionSnapshot row, String field) {
		try {
			return TossTransactionSnapshot.class.getMethod(field).invoke(row);
		}
		catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void reject(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
			assertThat(exception.getMessage()).isEqualTo("Invalid Toss transaction summary");
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
		});
	}
}
