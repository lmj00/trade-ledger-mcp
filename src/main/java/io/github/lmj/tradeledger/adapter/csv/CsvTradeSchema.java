package io.github.lmj.tradeledger.adapter.csv;

import java.util.List;
import java.util.Set;

final class CsvTradeSchema {

	static final String TRADE_ID = "trade_id";
	static final String SYMBOL = "symbol";
	static final String MARKET = "market";
	static final String DIRECTION = "direction";
	static final String QUANTITY = "quantity";
	static final String ENTRY_PRICE = "entry_price";
	static final String EXIT_PRICE = "exit_price";
	static final String FEES = "fees";
	static final String CURRENCY = "currency";
	static final String OPENED_AT = "opened_at";
	static final String CLOSED_AT = "closed_at";
	static final String STRATEGY = "strategy";

	static final List<String> HEADERS = List.of(
			TRADE_ID,
			SYMBOL,
			MARKET,
			DIRECTION,
			QUANTITY,
			ENTRY_PRICE,
			EXIT_PRICE,
			FEES,
			CURRENCY,
			OPENED_AT,
			CLOSED_AT,
			STRATEGY);

	static final Set<String> HEADER_SET = Set.copyOf(HEADERS);

	private CsvTradeSchema() {
	}
}
