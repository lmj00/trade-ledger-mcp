package io.github.lmj.tradeledger.mcp;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lmj.tradeledger.application.model.TossTransactionSnapshot;
import io.github.lmj.tradeledger.application.model.TossTransactionsSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Pure settlement-ledger projection; retains equal rows and explicit page metadata. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TossTransactionsResponse(
		String serviceId,
		List<TransactionResponse> transactions,
		@Schema(nullable = true) @Nullable String nextCursor,
		boolean hasNext) {

	public TossTransactionsResponse {
		transactions = List.copyOf(transactions);
	}

	public static TossTransactionsResponse from(TossTransactionsSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new TossTransactionsResponse("toss", snapshot.transactions().stream()
				.map(TransactionResponse::from).toList(), snapshot.nextCursor(), snapshot.hasNext());
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record TransactionResponse(
			String transactionType,
			String marketCountry,
			String currency,
			@Schema(nullable = true) @Nullable String symbol,
			@Schema(nullable = true) @Nullable String assetClass,
			@Schema(nullable = true) @Nullable String isinCode,
			@Schema(nullable = true) @Nullable String quantity,
			@Schema(nullable = true) @Nullable String filledPrice,
			@Schema(nullable = true) @Nullable String amount,
			String commission,
			String tax,
			@Schema(nullable = true) @Nullable String netAmount,
			@Schema(nullable = true) @Nullable String exchangeRate,
			String settlementDate,
			boolean settled,
			@Schema(nullable = true) @Nullable String transactionTime,
			boolean isCancellation) {

		public static TransactionResponse from(TossTransactionSnapshot row) {
			return new TransactionResponse(row.transactionType(), row.marketCountry(), row.currency(),
					row.symbol(), row.assetClass(), row.isinCode(), row.quantity(), row.filledPrice(), row.amount(),
					row.commission(), row.tax(), row.netAmount(), row.exchangeRate(), row.settlementDate().toString(),
					row.settled(), row.transactionTime() == null ? null : row.transactionTime().toString(), row.isCancellation());
		}
	}
}
