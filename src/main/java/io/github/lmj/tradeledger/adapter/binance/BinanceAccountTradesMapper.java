package io.github.lmj.tradeledger.adapter.binance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot;
import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot.Execution;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict, all-or-nothing mapping of a supplied Spot /api/v3/myTrades array.
 * The official account trade list is scoped to one account and symbol, with
 * default limit 500 and maximum 1000. Only a non-secret local account alias,
 * expected symbol, limit, optional orderId and inclusive fromId/time bounds and response JSON are accepted; no network,
 * authentication, storage, pagination, completed Trade or PnL is implemented.
 *
 * <p>Project policy (not Binance transport limits): at most 1,048,576 UTF-16
 * code units before parsing, nesting depth 64 (root array counts as one),
 * decoded strings/property names 256 code units, original number tokens and
 * decimal strings 128 code units, and 1000 response items. Unknown fields are
 * also bounded. Parser diagnostics and input text never escape in errors.
 *
 * @see <a href="https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account">Binance Spot account trade list: fields, int64 IDs, booleans and limits</a>
 */
public final class BinanceAccountTradesMapper {
	public static final int DEFAULT_LIMIT = 500;
	public static final int MAX_TRADES = 1_000;
	public static final int MAX_JSON_LENGTH = 1_048_576;
	public static final int MAX_NESTING_DEPTH = 64;
	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_NUMBER_LENGTH = 128;
	private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder()
					.maxNestingDepth(MAX_NESTING_DEPTH).maxStringLength(MAX_STRING_LENGTH)
					.maxNameLength(MAX_STRING_LENGTH).maxNumberLength(MAX_NUMBER_LENGTH).build())
			.build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	public BinanceAccountTradesSnapshot map(String accountAlias, String expectedSymbol, String json) {
		return map(accountAlias, expectedSymbol, DEFAULT_LIMIT, json);
	}

	public BinanceAccountTradesSnapshot map(String accountAlias, String expectedSymbol, int limit, String json) {
		return map(accountAlias, expectedSymbol, limit, null, null, null, null, json);
	}

	public BinanceAccountTradesSnapshot map(String accountAlias, BinanceAccountTradesRequest request, String json) {
		if (request == null) {
			throw invalid();
		}
		return map(accountAlias, request.symbol(), request.limit(), request.fromId(), request.startTime(), request.endTime(), request.orderId(), json);
	}

	private BinanceAccountTradesSnapshot map(String accountAlias, String expectedSymbol, int limit,
			Long fromId, Long startTime, Long endTime, Long orderId, String json) {
		if (!validLabel(accountAlias) || !validLabel(expectedSymbol) || limit < 1 || limit > MAX_TRADES
				|| json == null || json.length() > MAX_JSON_LENGTH) {
			throw invalid();
		}
		try {
			checkTokens(json, limit);
			JsonNode root = JSON.readTree(json);
			var trades = new ArrayList<Execution>(root.size());
			var ids = new HashSet<Long>();
			for (JsonNode node : root) {
				String symbol = text(node, "symbol");
				long id = integer(node, "id");
				if (!symbol.equals(expectedSymbol) || !ids.add(id) || (fromId != null && id < fromId)) {
					throw invalid();
				}
				long responseOrderId = integer(node, "orderId");
				if (orderId != null && responseOrderId != orderId) {
					throw invalid();
				}
				long time = integer(node, "time");
				if ((startTime != null && time < startTime) || (endTime != null && time > endTime)) {
					throw invalid();
				}
				trades.add(new Execution(symbol, id, responseOrderId, integer(node, "orderListId"),
						decimal(node, "price"), decimal(node, "qty"), decimal(node, "quoteQty"),
						decimal(node, "commission"), text(node, "commissionAsset"),
						Instant.ofEpochMilli(time), bool(node, "isBuyer"),
						bool(node, "isMaker"), bool(node, "isBestMatch")));
			}
			return new BinanceAccountTradesSnapshot("binance", accountAlias, expectedSymbol,
					trades, trades.size() == limit);
		}
		catch (RuntimeException exception) {
			// Deliberately discard parser causes, suppressed exceptions and source locations.
			throw invalid();
		}
	}

	private static void checkTokens(String json, int limit) {
		// Bound the entire response before building a tree, including ignored fields.
		// Exact token checks complement Jackson's potentially approximate length limits.
		try (var parser = JSON.createParser(json)) {
			if (parser.nextToken() != JsonToken.START_ARRAY) {
				throw invalid();
			}
			int depth = 1;
			int items = 0;
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (depth == 0) {
					throw invalid();
				}
				if (depth == 1 && token != JsonToken.END_ARRAY) {
					if (token != JsonToken.START_OBJECT || ++items > limit || items > MAX_TRADES) {
						throw invalid();
					}
				}
				if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
					if (++depth > MAX_NESTING_DEPTH) {
						throw invalid();
					}
				}
				else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
					depth--;
				}
				else if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
					if (parser.getString().length() > MAX_STRING_LENGTH) {
						throw invalid();
					}
				}
				else if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
					if (parser.getString().length() > MAX_NUMBER_LENGTH) {
						throw invalid();
					}
				}
			}
			if (depth != 0) {
				throw invalid();
			}
		}
	}

	private static boolean validLabel(String value) {
		return value != null && !value.isBlank() && value.length() <= MAX_STRING_LENGTH;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString() || value.asString().isBlank()) {
			throw invalid();
		}
		return value.asString();
	}

	private static String decimal(JsonNode node, String field) {
		String value = text(node, field);
		if (value.length() > MAX_NUMBER_LENGTH || !DECIMAL.matcher(value).matches()
				|| new BigDecimal(value).signum() < 0) {
			throw invalid();
		}
		return value;
	}

	private static long integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw invalid();
		}
		// Parsing the integral representation rejects overflow without floating point coercion.
		return Long.parseLong(value.toString());
	}

	private static boolean bool(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isBoolean()) {
			throw invalid();
		}
		return value.asBoolean();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Binance account trades response");
	}
}
