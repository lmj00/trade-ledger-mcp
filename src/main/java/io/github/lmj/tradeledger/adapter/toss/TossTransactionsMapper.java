package io.github.lmj.tradeledger.adapter.toss;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;

import io.github.lmj.tradeledger.application.model.TossTransactionSnapshot;
import io.github.lmj.tradeledger.application.model.TossTransactionsSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded mapping of one all-types settlement page; no transport or traversal. */
public final class TossTransactionsMapper {

	// Project resource limits; decimal field length 30 is specified by the official schema.
	public static final int MAX_JSON_LENGTH = 1_048_576;
	public static final int MAX_NESTING_DEPTH = 64;
	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_NUMBER_LENGTH = 128;
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder()
					.maxNestingDepth(MAX_NESTING_DEPTH).maxStringLength(MAX_STRING_LENGTH)
					.maxNameLength(MAX_STRING_LENGTH).maxNumberLength(MAX_NUMBER_LENGTH).build())
			.build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	public TossTransactionsSnapshot map(TossTransactionsRequest request, String json) {
		if (request == null || json == null || json.length() > MAX_JSON_LENGTH) {
			throw invalid();
		}
		try {
			checkTokens(json, request.limit());
			JsonNode envelope = object(JSON.readTree(json));
			if (envelope.get("error") != null) {
				throw invalid();
			}
			JsonNode page = object(envelope.get("result"));
			JsonNode transactions = page.get("transactions");
			if (transactions == null || !transactions.isArray() || transactions.size() > request.limit()
					|| transactions.size() > TossTransactionsSnapshot.MAX_TRANSACTIONS) {
				throw invalid();
			}
			String cursor = text(page, "nextCursor", true);
			boolean hasNext = bool(page, "hasNext");
			var snapshots = new ArrayList<TossTransactionSnapshot>(transactions.size());
			for (JsonNode transaction : transactions) {
				snapshots.add(mapTransaction(object(transaction)));
			}
			return new TossTransactionsSnapshot(snapshots, cursor, hasNext);
		}
		catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
			// No source text, parser/date diagnostics, causes or suppressed errors escape.
			throw invalid();
		}
	}

	private static TossTransactionSnapshot mapTransaction(JsonNode row) {
		String date = text(row, "settlementDate", false);
		if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
			throw invalid();
		}
		String time = text(row, "transactionTime", true);
		return new TossTransactionSnapshot(text(row, "transactionType", false),
				text(row, "marketCountry", false), text(row, "currency", false),
				text(row, "symbol", true), text(row, "assetClass", true), text(row, "isinCode", true),
				text(row, "quantity", true), text(row, "filledPrice", true), text(row, "amount", true),
				text(row, "commission", false), text(row, "tax", false), text(row, "netAmount", true),
				text(row, "exchangeRate", true), LocalDate.parse(date), bool(row, "settled"),
				time == null ? null : OffsetDateTime.parse(time).toInstant(), bool(row, "isCancellation"));
	}

	private static void checkTokens(String json, int maxTransactions) {
		// Two linear passes: validate the entire document before allocating any tree.
		// Ignored fields and damaged tails obey the same resource and syntax limits.
		try (var parser = JSON.createParser(json)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw invalid();
			}
			int depth = 1;
			String property = null;
			boolean resultObject = false;
			int transactionsDepth = -1;
			int count = 0;
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (depth == 0) {
					throw invalid();
				}
				// Count only direct result.transactions elements before tree creation.
				if (depth == transactionsDepth && token != JsonToken.END_ARRAY
						&& ++count > Math.min(maxTransactions, TossTransactionsSnapshot.MAX_TRANSACTIONS)) {
					throw invalid();
				}
				if (token == JsonToken.PROPERTY_NAME) {
					property = parser.getString();
					if (property.length() > MAX_STRING_LENGTH) {
						throw invalid();
					}
					continue;
				}
				if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
					if (depth == 1 && "result".equals(property) && token == JsonToken.START_OBJECT) {
						resultObject = true;
					}
					if (depth == 2 && resultObject && "transactions".equals(property)
							&& token == JsonToken.START_ARRAY) {
						transactionsDepth = 3;
					}
					if (++depth > MAX_NESTING_DEPTH) {
						throw invalid();
					}
				}
				else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
					if (depth == transactionsDepth) {
						transactionsDepth = -1;
					}
					if (depth == 2) {
						resultObject = false;
					}
					depth--;
				}
				else if (token == JsonToken.VALUE_STRING) {
					if (parser.getString().length() > MAX_STRING_LENGTH) {
						throw invalid();
					}
				}
				else if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
					if (parser.getString().length() > MAX_NUMBER_LENGTH) {
						throw invalid();
					}
				}
				property = null;
			}
			if (depth != 0) {
				throw invalid();
			}
		}
	}

	private static JsonNode object(JsonNode node) {
		if (node == null || !node.isObject()) {
			throw invalid();
		}
		return node;
	}

	private static String text(JsonNode node, String field, boolean nullable) {
		JsonNode value = node.get(field);
		if (value == null || (!value.isString() && !(nullable && value.isNull()))) {
			throw invalid();
		}
		return value.isNull() ? null : value.asString();
	}

	private static boolean bool(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isBoolean()) {
			throw invalid();
		}
		return value.asBoolean();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Toss transactions response", null);
	}
}
