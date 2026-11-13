package io.github.lmj.tradeledger.adapter.upbit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.application.model.ClosedOrdersSnapshot;
import io.github.lmj.tradeledger.application.model.ClosedOrdersSnapshot.Order;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict, all-or-nothing JSON summary mapping; never constructs detailed fills. */
public final class UpbitClosedOrdersMapper {
	// Project policy limits, not assertions about Upbit's transport limits.
	public static final int MAX_JSON_LENGTH = 1024 * 1024; // UTF-16 code units, checked before parsing
	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_NUMBER_LENGTH = 128;
	public static final int MAX_ORDERS = 1_000;
	private static final Pattern UUID = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	public ClosedOrdersSnapshot map(String json, UpbitClosedOrdersRequest request) {
		return map("upbit", json, request);
	}

	public ClosedOrdersSnapshot map(String serviceId, String json, UpbitClosedOrdersRequest request) {
		if (serviceId == null || serviceId.isBlank() || serviceId.length() > MAX_STRING_LENGTH
				|| request == null || json == null || json.length() > MAX_JSON_LENGTH) {
			throw invalid();
		}
		try {
			checkNumberTokenLengths(json);
			JsonNode root = JSON.readTree(json);
			if (root == null || !root.isArray() || root.size() > MAX_ORDERS || root.size() > request.limit()) {
				throw invalid();
			}
			checkLengths(root);
			var orders = new ArrayList<Order>(root.size());
			var ids = new HashSet<String>();
			for (JsonNode node : root) {
				if (!node.isObject()) {
					throw invalid();
				}
				String id = text(node, "uuid");
				if (!UUID.matcher(id).matches() || !ids.add(id.toLowerCase(Locale.ROOT))) {
					throw invalid();
				}
				String state = text(node, "state");
				if (!state.equals("done") && !state.equals("cancel")) {
					throw invalid();
				}
				String market = text(node, "market");
				if (request.market() != null && !request.market().equals(market)) {
					throw invalid();
				}
				if (request.state() != null ? !request.state().equals(state) : !request.states().contains(state)) {
					throw invalid();
				}
				JsonNode count = node.get("trades_count");
				if (count == null || !count.isIntegralNumber()) {
					throw invalid();
				}
				BigInteger tradesCount = new BigInteger(count.toString());
				if (tradesCount.signum() < 0) {
					throw invalid();
				}
				orders.add(new Order(id, market, text(node, "side"), text(node, "ord_type"),
						state, OffsetDateTime.parse(text(node, "created_at")).toInstant(),
						decimal(node, "executed_volume"), decimal(node, "paid_fee"), tradesCount));
			}
			return new ClosedOrdersSnapshot(serviceId, orders, orders.size() == request.limit());
		}
		catch (RuntimeException exception) {
			// Neither parser causes nor input text may escape through diagnostics.
			throw invalid();
		}
	}

	private static void checkNumberTokenLengths(String json) {
		// Tree numbers may lose fractional zeroes or normalize exponent notation.
		// Inspect every original token, including values in unknown nested fields.
		try (var parser = JSON.createParser(json)) {
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if ((token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
						&& parser.getString().length() > MAX_NUMBER_LENGTH) {
					throw invalid();
				}
			}
		}
	}

	private static void checkLengths(JsonNode node) {
		if (node.isObject()) {
			for (var property : node.properties()) {
				if (property.getKey().length() > MAX_STRING_LENGTH) {
					throw invalid();
				}
			}
		}
		if (node.isString() && node.asString().length() > MAX_STRING_LENGTH) {
			throw invalid();
		}
		if (node.isArray() || node.isObject()) {
			for (JsonNode child : node) {
				checkLengths(child);
			}
		}
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
		if (value.length() > MAX_NUMBER_LENGTH || !DECIMAL.matcher(value).matches()) {
			throw invalid();
		}
		BigDecimal amount = new BigDecimal(value);
		if (amount.signum() < 0) {
			throw invalid();
		}
		return amount.toPlainString();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Upbit closed orders response");
	}
}
