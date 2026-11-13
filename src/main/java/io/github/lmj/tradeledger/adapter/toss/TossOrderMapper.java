package io.github.lmj.tradeledger.adapter.toss;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot.ExecutionSummary;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded mapping of a single success envelope. No transport or pagination. */
public final class TossOrderMapper {

	// Project resource limits; decimal field length 30 comes from the official schema.
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

	public TossOrderSnapshot map(TossOrderRequest request, String json) {
		if (request == null || json == null || json.length() > MAX_JSON_LENGTH) {
			throw invalid();
		}
		try {
			checkTokens(json);
			JsonNode envelope = object(JSON.readTree(json));
			if (envelope.get("error") != null) {
				throw invalid();
			}
			JsonNode order = object(envelope.get("result"));
			String id = text(order, "orderId", true, false);
			if (!request.orderId().equals(id)) {
				throw invalid();
			}
			JsonNode execution = object(order.get("execution"));
			return new TossOrderSnapshot(id, text(order, "symbol", true, false),
					text(order, "side", true, false), text(order, "orderType", true, false),
					text(order, "timeInForce", true, false), text(order, "status", true, false),
					text(order, "price", false, true), text(order, "quantity", true, false),
					text(order, "orderAmount", false, true), text(order, "currency", true, false),
					instant(order, "orderedAt", true, false), instant(order, "canceledAt", false, true),
					new ExecutionSummary(text(execution, "filledQuantity", true, false),
							text(execution, "averageFilledPrice", true, true),
							text(execution, "filledAmount", true, true),
							text(execution, "commission", true, true), text(execution, "tax", true, true),
							instant(execution, "filledAt", true, true), date(execution, "settlementDate")));
		}
		catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
			// Neither parser/date diagnostics nor raw values may escape in the exception graph.
			throw invalid();
		}
	}

	private static void checkTokens(String json) {
		// Read the complete document, including ignored fields and damaged tails,
		// before allocating a tree or producing any summary. Two linear passes.
		try (var parser = JSON.createParser(json)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw invalid();
			}
			int depth = 1;
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (depth == 0) {
					throw invalid();
				}
				if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
					if (++depth > MAX_NESTING_DEPTH) {
						throw invalid();
					}
				}
				else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
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

	private static JsonNode object(JsonNode node) {
		if (node == null || !node.isObject()) {
			throw invalid();
		}
		return node;
	}

	private static String text(JsonNode node, String field, boolean required, boolean nullable) {
		JsonNode value = node.get(field);
		if (value == null) {
			if (required) {
				throw invalid();
			}
			return null;
		}
		if (value.isNull() && nullable) {
			return null;
		}
		if (!value.isString()) {
			throw invalid();
		}
		String result = value.asString();
		if (result.isBlank() || result.length() > MAX_STRING_LENGTH
				|| result.chars().anyMatch(Character::isISOControl)) {
			throw invalid();
		}
		return result;
	}

	private static Instant instant(JsonNode node, String field, boolean required, boolean nullable) {
		String value = text(node, field, required, nullable);
		return value == null ? null : OffsetDateTime.parse(value).toInstant();
	}

	private static LocalDate date(JsonNode node, String field) {
		String value = text(node, field, true, true);
		if (value == null) {
			return null;
		}
		if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
			throw invalid();
		}
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Toss order response");
	}
}
