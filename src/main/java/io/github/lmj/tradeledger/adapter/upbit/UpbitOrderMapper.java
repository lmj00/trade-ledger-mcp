package io.github.lmj.tradeledger.adapter.upbit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import io.github.lmj.tradeledger.application.model.OrderExecutionSnapshot;
import io.github.lmj.tradeledger.application.model.OrderExecutionSnapshot.Execution;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts Upbit JSON only; does not infer completed trades or match PnL. */
public final class UpbitOrderMapper {

	// Project policy limits, not claims about limits imposed by Upbit.
	public static final int MAX_DECIMAL_LENGTH = 128;
	public static final int MAX_STRING_LENGTH = 256;
	public static final int MAX_TRADES = 1_000;

	private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	public OrderExecutionSnapshot map(String json) {
		return map("upbit", json);
	}

	/** serviceId identifies the source service; no account credentials are needed. */
	public OrderExecutionSnapshot map(String serviceId, String json) {
		if (serviceId == null || serviceId.isBlank() || serviceId.length() > MAX_STRING_LENGTH) {
			throw invalid("serviceId");
		}
		if (json == null) {
			throw invalid("JSON");
		}
		JsonNode order;
		try {
			order = JSON.readTree(json);
		}
		catch (JacksonException exception) {
			// Parser exceptions can retain the payload. Do not attach them as causes.
			throw invalid("JSON");
		}
		object(order, "order");
		String id = text(order, "uuid");
		String market = text(order, "market");
		String side = text(order, "side");
		String type = text(order, "ord_type");
		String state = text(order, "state");
		Instant createdAt = instant(order, "created_at");
		String executedVolume = decimal(order, "executed_volume", false);
		String paidFee = decimal(order, "paid_fee", false);
		JsonNode trades = order.get("trades");
		if (trades == null || !trades.isArray() || trades.size() > MAX_TRADES) {
			throw invalid("trades");
		}
		var executions = new ArrayList<Execution>(trades.size());
		var ids = new HashSet<String>();
		for (JsonNode trade : trades) {
			object(trade, "trade");
			String tradeId = text(trade, "uuid");
			if (!ids.add(tradeId)) {
				throw invalid("duplicate trade uuid");
			}
			executions.add(new Execution(tradeId, text(trade, "market"), text(trade, "side"),
					decimal(trade, "price", true), decimal(trade, "volume", true),
					decimal(trade, "funds", true), instant(trade, "created_at")));
		}
		return new OrderExecutionSnapshot(serviceId, id, market, side, type, state,
				createdAt, executedVolume, paidFee, executions);
	}

	private static void object(JsonNode node, String field) {
		if (node == null || !node.isObject()) {
			throw invalid(field);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString()) {
			throw invalid(field);
		}
		String text = value.asString();
		if (text.isBlank() || text.length() > MAX_STRING_LENGTH) {
			throw invalid(field);
		}
		return text;
	}

	private static String decimal(JsonNode node, String field, boolean positive) {
		String value = text(node, field);
		if (value.length() > MAX_DECIMAL_LENGTH || !DECIMAL.matcher(value).matches()) {
			throw invalid(field);
		}
		BigDecimal amount = new BigDecimal(value);
		if (amount.signum() < 0 || (positive && amount.signum() == 0)) {
			throw invalid(field);
		}
		return amount.toPlainString();
	}

	private static Instant instant(JsonNode node, String field) {
		String value = text(node, field);
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException exception) {
			throw invalid(field);
		}
	}

	private static IllegalArgumentException invalid(String field) {
		return new IllegalArgumentException("Invalid Upbit order field: " + field);
	}
}
