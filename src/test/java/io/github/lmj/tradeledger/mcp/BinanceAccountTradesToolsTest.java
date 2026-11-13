package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.lmj.tradeledger.TradeLedgerMcpApplication;
import io.github.lmj.tradeledger.adapter.binance.BinanceAccountTradesClient;
import io.github.lmj.tradeledger.adapter.binance.BinanceAccountTradesRequest;
import io.github.lmj.tradeledger.adapter.binance.BinanceApiException;
import io.github.lmj.tradeledger.adapter.binance.BinanceErrorCode;
import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot;
import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = {TradeLedgerMcpApplication.class, BinanceAccountTradesToolsTest.LocalConfiguration.class},
		properties = "trade-ledger.journal.root=src/test/resources/journals")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = BinanceAccountTradesToolsTest.TransportCleanupListener.class,
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BinanceAccountTradesToolsTest {
	private static final String KEY = "fake-binance-mcp-api-key";
	private static final String SECRET = "fake-binance-mcp-secret-key";
	private static final String MARKER = "private-binance-mcp-response";
	private static final String ALIAS = "local-display-account";
	private static final String NAME = "get_binance_account_trades";
	private static final String EXECUTIONS_NAME = "get_binance_account_executions";
	private static final List<String> NAMES = List.of(NAME, EXECUTIONS_NAME);
	private static final String DISABLE = "jdk.httpclient.disableRetryConnect";
	private static final String RETRIES = "jdk.httpclient.redirects.retrylimit";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = Map.of("BINANCE_API_KEY", KEY, "BINANCE_SECRET_KEY", SECRET);
	private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000000123L), ZoneOffset.UTC);
	private static final String FILL = """
			{"symbol":"BTCUSDT","id":9007199254740993,"orderId":9223372036854775807,"orderListId":-1,
			"price":"9007199254740993.12345678900","qty":"0.02000000000000000100",
			"quoteQty":"180143985094819.87147665314740999300","commission":"0.00001000000000000000",
			"commissionAsset":"BNB","time":1499865549590,"isBuyer":true,"isMaker":false,"isBestMatch":true}
			""".strip();

	@Autowired
	ApplicationContext context;
	@Autowired
	LocalBinance server;
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

	@BeforeEach
	void resetAndCaptureLogs() {
		server.keys.set(KEYS);
		server.status.set(200);
		server.body.set("[" + FILL + "]");
		server.requests.set(0);
		server.signatures.clear();
		server.paths.clear();
		server.headers.clear();
		server.releaseBody.set(new CountDownLatch(1));
		logs.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
		for (String name : List.of("io.github.lmj.tradeledger", "org.springframework.ai.mcp", "io.modelcontextprotocol")) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			previousLevels.put(logger, logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}
	}

	@AfterEach
	void stopCapturingLogs() {
		server.releaseBody.get().countDown();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
		previousLevels.forEach(Logger::setLevel);
		logs.stop();
	}

	@Test
	void registersOptionalStringFromIdAndKeepsAllSevenTools() {
		List<?> specs = (List<?>) context.getBean("toolSpecs");
		List<SyncToolSpecification> tools = specs.stream().filter(SyncToolSpecification.class::isInstance)
				.map(SyncToolSpecification.class::cast).toList();
		assertThat(tools).extracting(spec -> spec.tool().name()).containsExactlyInAnyOrder(
				"summarize_journal", "analyze_trades", "get_upbit_order", "get_upbit_closed_orders",
				NAME, "get_upbit_order_executions", EXECUTIONS_NAME);
		var mapper = JsonMapper.builder().build();
		var schema = mapper.valueToTree(tools.stream().filter(spec -> spec.tool().name().equals(NAME))
				.findFirst().orElseThrow().tool().inputSchema());
		assertThat(asMap(tools.stream().filter(spec -> spec.tool().name().equals(NAME))
				.findFirst().orElseThrow().tool().inputSchema().get("properties")))
				.containsOnlyKeys("accountAlias", "symbol", "limit", "fromId");
		assertThat(schema.get("properties").get("accountAlias").get("type").asString()).isEqualTo("string");
		assertThat(schema.get("properties").get("symbol").get("type").asString()).isEqualTo("string");
		assertThat(schema.get("properties").get("limit").get("type").asString()).isEqualTo("integer");
		assertThat(schema.get("properties").get("fromId").get("type").asString()).isEqualTo("string");
		assertThat(schema.get("required")).isEqualTo(mapper.valueToTree(List.of("accountAlias", "symbol")));
		var commonSchema = mapper.valueToTree(tools.stream()
				.filter(spec -> spec.tool().name().equals(EXECUTIONS_NAME)).findFirst().orElseThrow()
				.tool().inputSchema());
		assertThat(commonSchema.get("properties").has("fromId")).isFalse();
	}

	@Test
	void registeredFromIdAcceptsExactBoundaryIdsAndKeepsResponseShape() {
		for (String id : List.of("0", "9007199254740993", "9223372036854775807")) {
			server.body.set("[" + FILL.replace("\"id\":9007199254740993", "\"id\":" + id) + "]");
			Map<String, Object> args = arguments(ALIAS, "BTCUSDT", 1);
			args.put("fromId", id);
			int before = server.requests.get();
			CallToolResult result = call(args);
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			Map<String, Object> response = asMap(result.structuredContent());
			assertThat(response).containsOnlyKeys("serviceId", "accountAlias", "symbol", "trades", "possiblyTruncated")
					.containsEntry("possiblyTruncated", true);
			assertThat(asMap(((List<?>) response.get("trades")).getFirst())).containsEntry("id", id);
			assertThat(server.requests).hasValue(before + 1);
			assertThat(server.paths.getLast()).startsWith("GET /api/v3/myTrades?symbol=BTCUSDT&limit=1&fromId="
					+ id + "&recvWindow=5000&timestamp=1700000000123&signature=");
			assertMatchingSuccessContent(result);
			assertNoSecrets(result);
		}
		server.body.set("[]");
		Map<String, Object> args = arguments(ALIAS, "BTCUSDT", 1);
		args.put("fromId", "9223372036854775807");
		CallToolResult empty = call(args);
		assertThat(empty.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(empty.structuredContent())).containsEntry("trades", List.of())
				.containsEntry("possiblyTruncated", false);
		assertThat(server.requests).hasValue(4);
		assertNoSecrets(empty);
	}

	@Test
	void rejectsInvalidFromIdBeforeLazyProviderAndHttp() {
		@SuppressWarnings("unchecked")
		ObjectProvider<BinanceAccountTradesClient> provider = mock(ObjectProvider.class);
		var tools = new BinanceAccountTradesTools(provider);
		for (String id : List.of("", " ", "\t", "-1", "-0", "+1", "1.0", "1e3", "00", "01",
				" 1", "1 ", "１", "١", "9223372036854775808", "1".repeat(10000), MARKER)) {
			assertThatThrownBy(() -> tools.getBinanceAccountTrades(ALIAS, "BTCUSDT", null, id))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Binance request failed: BAD_REQUEST")
					.hasNoCause().satisfies(BinanceAccountTradesToolsTest::assertSafeThrowable);
			Map<String, Object> args = arguments(ALIAS, "BTCUSDT", null);
			args.put("fromId", id);
			assertSafeError(call(args), "BAD_REQUEST");
		}
		verifyNoInteractions(provider);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	void registeredFromIdRejectsBelowBoundWithoutPartialResults() {
		String below = FILL.replace("\"id\":9007199254740993", "\"id\":9007199254740992");
		Map<String, Object> args = arguments(ALIAS, "BTCUSDT", 2);
		args.put("fromId", "9007199254740993");
		for (String body : List.of("[" + below + "]", "[" + FILL + "," + below + "]")) {
			server.body.set(body);
			CallToolResult result = call(args);
			assertSafeError(result, "INVALID_RESPONSE");
			assertThat(result.structuredContent()).isNull();
		}
		assertThat(server.requests).hasValue(2);
	}

	@Test
	void registeredHandlerPreservesEveryFillFieldAndExactStringIds() {
		String sell = FILL.replace("9007199254740993,", "9223372036854775807,")
				.replace("\"orderListId\":-1", "\"orderListId\":9007199254740993")
				.replace("\"isBuyer\":true", "\"isBuyer\":false")
				.replace("\"isMaker\":false", "\"isMaker\":true")
				.replace("\"isBestMatch\":true", "\"isBestMatch\":false").replace("BNB", "USDT");
		server.body.set("[" + FILL + "," + sell + "]");
		CallToolResult result = call(arguments(ALIAS, "BTCUSDT", null));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> response = asMap(result.structuredContent());
		assertThat(response).containsOnlyKeys("serviceId", "accountAlias", "symbol", "trades", "possiblyTruncated")
				.containsEntry("serviceId", "binance").containsEntry("accountAlias", ALIAS)
				.containsEntry("symbol", "BTCUSDT").containsEntry("possiblyTruncated", false);
		List<?> trades = (List<?>) response.get("trades");
		assertThat(trades).hasSize(2);
		for (int index = 0; index < trades.size(); index++) {
			assertThat(asMap(trades.get(index)))
					.containsOnlyKeys("symbol", "id", "orderId", "orderListId", "price", "qty", "quoteQty",
							"commission", "commissionAsset", "time", "isBuyer", "isMaker", "isBestMatch")
					.containsEntry("symbol", "BTCUSDT")
					.containsEntry("id", index == 0 ? "9007199254740993" : "9223372036854775807")
					.containsEntry("orderId", "9223372036854775807")
					.containsEntry("orderListId", index == 0 ? "-1" : "9007199254740993")
					.containsEntry("price", "9007199254740993.12345678900")
					.containsEntry("qty", "0.02000000000000000100")
					.containsEntry("quoteQty", "180143985094819.87147665314740999300")
					.containsEntry("commission", "0.00001000000000000000")
					.containsEntry("commissionAsset", index == 0 ? "BNB" : "USDT")
					.containsEntry("time", "2017-07-12T13:19:09.590Z")
					.containsEntry("isBuyer", index == 0).containsEntry("isMaker", index != 0)
					.containsEntry("isBestMatch", index == 0);
		}
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).hasSize(1);
		assertThat(server.paths.getFirst()).startsWith("GET /api/v3/myTrades?symbol=BTCUSDT&limit=500"
				+ "&recvWindow=5000&timestamp=1700000000123&signature=").doesNotContain(ALIAS, KEY, SECRET);
		assertThat(server.headers).hasSize(1);
		assertThat(server.headers.getFirst()).contains(KEY).doesNotContain(ALIAS, SECRET);
		assertNoSecrets(result);
	}

	@Test
	void registeredCommonHandlerPreservesExecutionsAndSeparateSourceFees() {
		String sell = FILL.replace("\"id\":9007199254740993", "\"id\":9223372036854775807")
				.replace("\"isBuyer\":true", "\"isBuyer\":false").replace("BNB", "USDT");
		server.body.set("[" + FILL + "," + sell + "]");
		CallToolResult result = call(context, EXECUTIONS_NAME, arguments(ALIAS, "BTCUSDT", null));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> response = asMap(result.structuredContent());
		assertThat(response).containsOnlyKeys("serviceId", "accountAlias", "symbol", "queryScope", "orderId",
				"possiblyTruncated", "executions", "fees")
				.containsEntry("serviceId", "binance").containsEntry("accountAlias", ALIAS)
				.containsEntry("symbol", "BTCUSDT").containsEntry("queryScope", "RECENT_SYMBOL")
				.containsEntry("orderId", null).containsEntry("possiblyTruncated", false);
		List<?> executions = (List<?>) response.get("executions");
		List<?> fees = (List<?>) response.get("fees");
		assertThat(executions).hasSize(2);
		assertThat(fees).hasSize(2);
		for (int index = 0; index < executions.size(); index++) {
			String id = index == 0 ? "9007199254740993" : "9223372036854775807";
			assertThat(asMap(executions.get(index)))
					.containsOnlyKeys("serviceId", "accountAlias", "symbol", "executionId", "orderId", "side",
							"price", "quantity", "amount", "executedAt")
					.containsEntry("serviceId", "binance").containsEntry("accountAlias", ALIAS)
					.containsEntry("symbol", "BTCUSDT").containsEntry("executionId", id)
					.containsEntry("orderId", "9223372036854775807")
					.containsEntry("side", index == 0 ? "BUY" : "SELL")
					.containsEntry("price", "9007199254740993.12345678900")
					.containsEntry("quantity", "0.02000000000000000100")
					.containsEntry("amount", "180143985094819.87147665314740999300")
					.containsEntry("executedAt", "2017-07-12T13:19:09.590Z");
			assertThat(asMap(fees.get(index))).containsOnlyKeys("scope", "targetId", "amount", "currency")
					.containsEntry("scope", "FILL").containsEntry("targetId", id)
					.containsEntry("amount", "0.00001000000000000000")
					.containsEntry("currency", index == 0 ? "BNB" : "USDT");
		}
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).hasSize(1);
		assertThat(server.paths.getFirst()).startsWith("GET /api/v3/myTrades?symbol=BTCUSDT&limit=500"
				+ "&recvWindow=5000&timestamp=1700000000123&signature=").doesNotContain(ALIAS, KEY, SECRET);
		assertThat(server.headers).hasSize(1);
		assertThat(server.headers.getFirst()).contains(KEY).doesNotContain(ALIAS, SECRET);
		assertNoSecrets(result);
	}

	@ParameterizedTest
	@MethodSource("limitBoundaries")
	void registeredHandlerPreservesLimitBoundariesAndMarksTruncation(String name, int limit) {
		server.body.set(IntStream.range(0, limit)
				.mapToObj(index -> FILL.replace("\"id\":9007199254740993", "\"id\":" + index))
				.collect(Collectors.joining(",", "[", "]")));
		CallToolResult result = call(context, name, arguments(ALIAS, "BTCUSDT", limit));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsEntry("possiblyTruncated", true);
		assertThat((List<?>) asMap(result.structuredContent()).get(name.equals(NAME) ? "trades" : "executions"))
				.hasSize(limit);
		if (name.equals(EXECUTIONS_NAME)) {
			assertThat((List<?>) asMap(result.structuredContent()).get("fees")).hasSize(limit);
			assertThat(asMap(result.structuredContent())).containsEntry("queryScope", "RECENT_SYMBOL")
					.containsEntry("orderId", null);
		}
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).hasSize(1);
		assertThat(server.paths.getFirst()).startsWith("GET /api/v3/myTrades?symbol=BTCUSDT&limit=" + limit
				+ "&recvWindow=5000&timestamp=1700000000123&signature=").doesNotContain(ALIAS, KEY, SECRET);
		assertThat(server.headers).hasSize(1);
		assertThat(server.headers.getFirst()).contains(KEY).doesNotContain(ALIAS, SECRET);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@ParameterizedTest
	@MethodSource("validAliases")
	void acceptsValidAliasesWithoutTrimmingOrSendingThem(String name, String kind) {
		String alias = switch (kind) {
			case "ascii-boundary" -> "x".repeat(256);
			case "surrogate-boundary" -> "😀".repeat(128);
			default -> " 한글😀 ";
		};
		String symbol = kind.equals("unicode") ? "한글😀" : "s".repeat(256);
		server.body.set("[]");
		CallToolResult result = call(context, name, arguments(alias, symbol, null));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsEntry("accountAlias", alias)
				.containsEntry("symbol", symbol).containsEntry(name.equals(NAME) ? "trades" : "executions", List.of())
				.containsEntry("possiblyTruncated", false);
		if (name.equals(EXECUTIONS_NAME)) {
			assertThat(asMap(result.structuredContent())).containsEntry("fees", List.of())
					.containsEntry("queryScope", "RECENT_SYMBOL").containsEntry("orderId", null);
		}
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths.getFirst()).contains("&limit=500&").doesNotContain(alias);
		if (kind.equals("unicode")) {
			assertThat(server.paths.getFirst()).contains("symbol=%ED%95%9C%EA%B8%80%F0%9F%98%80&");
		}
		assertThat(server.headers.getFirst()).doesNotContain(alias);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	static Stream<Arguments> limitBoundaries() {
		return NAMES.stream().flatMap(name -> Stream.of(1, 1000).map(limit -> Arguments.of(name, limit)));
	}

	static Stream<Arguments> validAliases() {
		return NAMES.stream().flatMap(name -> Stream.of("ascii-boundary", "surrogate-boundary", "unicode")
				.map(kind -> Arguments.of(name, kind)));
	}

	static Stream<Map<String, Object>> invalidRequests() {
		List<Map<String, Object>> requests = new ArrayList<>();
		for (String invalid : new String[] {null, "", " ", "x".repeat(257), "😀".repeat(128) + "x",
				"a\n", "\u007f", "\u0085", "\ud800", "\udc00", "a\ud800b", "a\ud800\ud800"}) {
			requests.add(arguments(invalid, "BTCUSDT", null));
			requests.add(arguments(ALIAS, invalid, null));
		}
		requests.add(arguments(ALIAS, "BTCUSDT", 0));
		requests.add(arguments(ALIAS, "BTCUSDT", 1001));
		requests.add(arguments(MARKER + KEY + SECRET + "\n", "BTCUSDT", null));
		requests.add(arguments(ALIAS, MARKER + KEY + SECRET + "\n", null));
		return requests.stream();
	}

	@ParameterizedTest
	@MethodSource("invalidRequests")
	@SuppressWarnings("unchecked")
	void invalidInputNeverObtainsClientOrSendsHttp(Map<String, Object> arguments) {
		ObjectProvider<BinanceAccountTradesClient> provider = mock(ObjectProvider.class);
		BinanceAccountTradesTools tools = new BinanceAccountTradesTools(provider);
		assertThatThrownBy(() -> tools.getBinanceAccountTrades((String) arguments.get("accountAlias"),
				(String) arguments.get("symbol"), (Integer) arguments.get("limit")))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Binance request failed: BAD_REQUEST")
				.hasNoCause().satisfies(failure -> assertSafeThrowable(failure));
		assertThatThrownBy(() -> tools.getBinanceAccountExecutions((String) arguments.get("accountAlias"),
				(String) arguments.get("symbol"), (Integer) arguments.get("limit")))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Binance request failed: BAD_REQUEST")
				.hasNoCause().satisfies(failure -> assertSafeThrowable(failure));
		verifyNoInteractions(provider);
		server.keys.set(Map.of());
		int creations = server.clientCreations.get();
		assertSafeError(call(arguments), "BAD_REQUEST");
		assertSafeError(call(context, EXECUTIONS_NAME, arguments), "BAD_REQUEST");
		assertThat(server.clientCreations).hasValue(creations);
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {NAME, EXECUTIONS_NAME})
	void missingCredentialsNeverSendsHttp(String name) {
		server.keys.set(Map.of());
		assertSafeError(call(context, name, arguments(ALIAS, "BTCUSDT", null)), "MISSING_CREDENTIALS");
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@MethodSource("httpErrors")
	void registeredErrorsDiscardRawDetailsAndNeverRetry(String name, int status, String category) {
		server.status.set(status);
		server.body.set(MARKER);
		assertSafeError(call(context, name, arguments(ALIAS, "BTCUSDT", null)), category);
		assertThat(server.requests).hasValue(1);
	}

	@ParameterizedTest
	@MethodSource("exchangeErrors")
	void registeredHandlerExposesSafeExchangeCodeWithoutDiagnosingItsCause(String name, int code, String category) {
		server.status.set(401);
		server.body.set("{\"code\":" + code + ",\"msg\":\"" + MARKER + "\"}");
		assertSafeError(call(context, name, arguments(ALIAS, "BTCUSDT", null)), category);
		assertThat(server.requests).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {NAME, EXECUTIONS_NAME})
	void oversizedResponseIsSafeAndNeverRetried(String name) {
		server.body.set(MARKER + "x".repeat(1024 * 1024));
		assertSafeError(call(context, name, arguments(ALIAS, "BTCUSDT", null)), "RESPONSE_TOO_LARGE");
		assertThat(server.requests).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {NAME, EXECUTIONS_NAME})
	void fullBodyTimeoutIsSafeAndNeverRetried(String name) {
		server.status.set(-2);
		try {
			assertSafeError(call(context, name, arguments(ALIAS, "BTCUSDT", null)), "TIMEOUT");
			assertThat(server.requests).hasValue(1);
		}
		finally {
			server.releaseBody.get().countDown();
		}
	}

	static Stream<Arguments> httpErrors() {
		Map<Integer, String> errors = Map.of(400, "BAD_REQUEST", 401, "AUTHENTICATION_FAILED",
				403, "SECURITY_RESTRICTION", 418, "IP_BLOCKED", 429, "RATE_LIMITED", 500, "SERVER_ERROR",
				503, "SERVER_ERROR", 302, "REDIRECT_REJECTED", 200, "INVALID_RESPONSE", -1, "NETWORK_ERROR");
		return NAMES.stream().flatMap(name -> errors.entrySet().stream()
				.map(error -> Arguments.of(name, error.getKey(), error.getValue())));
	}

	static Stream<Arguments> exchangeErrors() {
		Map<Integer, String> errors = Map.of(-1021, "INVALID_TIMESTAMP", -1022, "INVALID_SIGNATURE",
				-2014, "INVALID_API_KEY_FORMAT", -2015, "KEY_IP_OR_PERMISSION_REJECTED");
		return NAMES.stream().flatMap(name -> errors.entrySet().stream()
				.map(error -> Arguments.of(name, error.getKey(), error.getValue())));
	}

	@Test
	@SuppressWarnings("unchecked")
	void initializationWrappersAndUnexpectedExceptionsLoseMessagesCausesAndStacks() throws Exception {
		ObjectProvider<BinanceAccountTradesClient> provider = mock(ObjectProvider.class);
		BinanceAccountTradesTools tools = new BinanceAccountTradesTools(provider);
		for (BinanceErrorCode code : BinanceErrorCode.values()) {
			doThrow(new BeanCreationException(MARKER + KEY + SECRET, safeException(code)))
					.when(provider).getObject();
			assertThatThrownBy(() -> tools.getBinanceAccountTrades(ALIAS, "BTCUSDT", null))
					.hasMessage("Binance request failed: " + code.name()).hasNoCause()
					.satisfies(failure -> assertSafeThrowable(failure));
			assertThatThrownBy(() -> tools.getBinanceAccountExecutions(ALIAS, "BTCUSDT", null))
					.hasMessage("Binance request failed: " + code.name()).hasNoCause()
					.satisfies(failure -> assertSafeThrowable(failure));
		}
		doThrow(new BeanCreationException(MARKER + KEY, new IllegalStateException(SECRET)))
				.when(provider).getObject();
		assertThatThrownBy(() -> tools.getBinanceAccountTrades(ALIAS, "BTCUSDT", null))
				.hasMessage("Binance request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(failure -> assertSafeThrowable(failure));
		assertThatThrownBy(() -> tools.getBinanceAccountExecutions(ALIAS, "BTCUSDT", null))
				.hasMessage("Binance request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(failure -> assertSafeThrowable(failure));
	}

	@ParameterizedTest
	@ValueSource(strings = {"UNSAFE_TRANSPORT_CONFIGURATION", "MISSING_CREDENTIALS", "INTERNAL_ERROR"})
	void registeredLazyInitializationFailuresAreSafeInResponsesAndLogs(String category) throws Exception {
		RuntimeException failure = new BeanCreationException(MARKER + KEY + SECRET,
				category.equals("INTERNAL_ERROR") ? new IllegalStateException(MARKER + SECRET)
						: safeException(BinanceErrorCode.valueOf(category)));
		new ApplicationContextRunner().withUserConfiguration(RunnerConfiguration.class)
				.withPropertyValues("trade-ledger.journal.root=src/test/resources/journals",
						"spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=true")
				.withBean("failingBinanceClient", BinanceAccountTradesClient.class, () -> { throw failure; },
						definition -> { definition.setPrimary(true); definition.setLazyInit(true); })
				.run(application -> {
					assertThat(application).hasNotFailed();
					assertThat(application).doesNotHaveBean("localUpbitClient");
					assertThat(application).doesNotHaveBean("localBinanceClient");
					assertSafeError(call(application, NAME, arguments(ALIAS, "BTCUSDT", null)), category);
					assertSafeError(call(application, EXECUTIONS_NAME, arguments(ALIAS, "BTCUSDT", null)), category);
				});
		assertThat(server.requests).hasValue(0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void unexpectedCommonConversionFailureIsSafeAndDoesNotQueryAgain() {
		BinanceAccountTradesClient faultyClient = mock(BinanceAccountTradesClient.class);
		when(faultyClient.getAccountTrades(eq(ALIAS), any(BinanceAccountTradesRequest.class)))
				.thenReturn(new BinanceAccountTradesSnapshot(MARKER + KEY + SECRET + "x".repeat(256),
						ALIAS, "BTCUSDT", List.of(), false));
		ObjectProvider<BinanceAccountTradesClient> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(faultyClient);
		assertThatThrownBy(() -> new BinanceAccountTradesTools(provider)
				.getBinanceAccountExecutions(ALIAS, "BTCUSDT", null))
				.hasMessage("Binance request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(BinanceAccountTradesToolsTest::assertSafeThrowable);
		verify(provider).getObject();
		verifyNoMoreInteractions(provider);
		verify(faultyClient).getAccountTrades(eq(ALIAS), any(BinanceAccountTradesRequest.class));
		new ApplicationContextRunner().withUserConfiguration(RunnerConfiguration.class)
				.withPropertyValues("trade-ledger.journal.root=src/test/resources/journals",
						"spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=true")
				.withBean("faultyBinanceClient", BinanceAccountTradesClient.class, () -> faultyClient,
						definition -> {
							definition.setPrimary(true);
							definition.setLazyInit(true);
							definition.setDestroyMethodName("close");
						})
				.run(application -> {
					assertThat(application).hasNotFailed();
					assertSafeError(call(application, EXECUTIONS_NAME, arguments(ALIAS, "BTCUSDT", null)), "INTERNAL_ERROR");
				});
		verify(faultyClient, times(2)).getAccountTrades(eq(ALIAS), any(BinanceAccountTradesRequest.class));
		// The runner destroys the supplied bean when its context closes.
		verify(faultyClient).close();
		verifyNoMoreInteractions(faultyClient);
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@SuppressWarnings("unchecked")
	void commonConversionPreservesSourceTruncationRatherThanRecomputingIt(boolean truncated) {
		BinanceAccountTradesClient suppliedClient = mock(BinanceAccountTradesClient.class);
		when(suppliedClient.getAccountTrades(eq(ALIAS), any(BinanceAccountTradesRequest.class)))
				.thenReturn(new BinanceAccountTradesSnapshot("binance", ALIAS, "BTCUSDT", List.of(), truncated));
		ObjectProvider<BinanceAccountTradesClient> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(suppliedClient);
		CommonExecutionResponse result = new BinanceAccountTradesTools(provider)
				.getBinanceAccountExecutions(ALIAS, "BTCUSDT", null);
		assertThat(result.possiblyTruncated()).isEqualTo(truncated);
		assertThat(result.queryScope()).isEqualTo("RECENT_SYMBOL");
		assertThat(result.orderId()).isNull();
		assertThat(result.executions()).isEmpty();
		assertThat(result.fees()).isEmpty();
		verify(provider).getObject();
		verify(suppliedClient).getAccountTrades(eq(ALIAS), any(BinanceAccountTradesRequest.class));
		verifyNoMoreInteractions(provider, suppliedClient);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	void independentJvmStartsWithoutKeysOrOptionsAndRejectsUnsafeLookupAndDiagnosticLogging() throws Exception {
		List<String> valid = List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=1");
		assertThat(runStartupProbe(List.of(), "missing")).contains("PROBE_OK");
		List<String> logging = new ArrayList<>(valid);
		logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
		assertThat(runStartupProbe(logging, "logging")).contains("PROBE_OK");
		assertThat(runStartupProbe(logging, "clearedLogging")).contains("PROBE_OK");
	}

	private static String runStartupProbe(List<String> options, String mode) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).isNotBlank();
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, StartupProbe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
				"BINANCE_API_KEY", "BINANCE_SECRET_KEY", "UPBIT_ACCESS_KEY", "UPBIT_SECRET_KEY")) {
			builder.environment().remove(variable);
		}
		Process process = builder.start();
		try (var input = process.getInputStream()) {
			process.getOutputStream().close();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			boolean exited = false;
			while (true) {
				int available;
				while ((available = input.available()) > 0) {
					int count = input.read(buffer, 0, Math.min(available, buffer.length));
					if (count < 0) { break; }
					output.write(buffer, 0, count);
					assertThat(output.size()).isLessThanOrEqualTo(16384);
				}
				if (exited) { break; }
				assertThat(System.nanoTime() < deadline).as("Child JVM deadline").isTrue();
				exited = process.waitFor(25, TimeUnit.MILLISECONDS);
			}
			String observed = output.toString(StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("Child output: %s", observed).isZero();
			assertThat(observed).doesNotContain(KEY, SECRET, MARKER);
			return observed;
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	/** A separate JVM has no inherited HTTP settings or real credentials; only loopback is used. */
	public static final class StartupProbe {
		public static void main(String[] args) {
			try {
				AtomicReference<LocalBinance> retained = new AtomicReference<>();
				new ApplicationContextRunner()
						.withUserConfiguration(RunnerConfiguration.class, LocalConfiguration.class)
						.withPropertyValues("trade-ledger.journal.root=src/test/resources/journals",
								"spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=true",
								"logging.console.enabled=false", "spring.main.banner-mode=off")
						.run(application -> {
							assertThat(application).hasNotFailed();
							assertThat(application).doesNotHaveBean("localUpbitClient");
							LocalBinance local = application.getBean(LocalBinance.class);
							retained.set(local);
							local.keys.set(Map.of());
							for (String bean : List.of("upbitOrderClient", "binanceAccountTradesClient", "localBinanceClient")) {
								assertThat(application.getBeanFactory().containsSingleton(bean)).isFalse();
							}
							CallToolResult summary = call(application, "summarize_journal", Map.of("journalName", "sample-trades"));
							assertThat(summary.isError()).isNotEqualTo(Boolean.TRUE);
							assertThat(asMap(summary.structuredContent())).containsEntry("totalNetPnl", "355.78645");
							Map<String, Object> trade = Map.ofEntries(
									Map.entry("tradeId", "probe-trade"), Map.entry("symbol", "BTC-USDT"),
									Map.entry("market", "CRYPTO"), Map.entry("direction", "LONG"),
									Map.entry("quantity", "1"), Map.entry("entryPrice", "100"),
									Map.entry("exitPrice", "110"), Map.entry("fees", "1"),
									Map.entry("currency", "USDT"), Map.entry("openedAt", "2026-07-01T00:00:00Z"),
									Map.entry("closedAt", "2026-07-01T01:00:00Z"), Map.entry("strategy", "probe"));
							CallToolResult analysis = call(application, "analyze_trades", Map.of("trades", List.of(trade)));
							assertThat(analysis.isError()).isNotEqualTo(Boolean.TRUE);
							assertThat(asMap(analysis.structuredContent())).containsEntry("totalNetPnl", "9");
							for (String bean : List.of("upbitOrderClient", "binanceAccountTradesClient", "localBinanceClient")) {
								assertThat(application.getBeanFactory().containsSingleton(bean)).isFalse();
							}
							if (args[0].equals("clearedLogging")) { System.clearProperty(HTTP_LOG); }
							int attempts = 0;
							for (String name : NAMES) {
								CallToolResult result = call(application, name, arguments(ALIAS, "BTCUSDT", null));
								assertThat(result.isError()).isTrue();
								assertThat(result.content().toString()).contains("Binance request failed: UNSAFE_TRANSPORT_CONFIGURATION")
										.doesNotContain(KEY, SECRET, MARKER);
								assertThat(local.requests).hasValue(0);
								assertThat(local.clientCreations).hasValue(++attempts);
							}
						});
				assertThat(retained.get().closed).isTrue();
				System.out.println("PROBE_OK");
			}
			catch (Throwable failure) {
				failure.printStackTrace();
				System.exit(1);
			}
		}
	}

	private CallToolResult call(Map<String, Object> arguments) {
		return call(context, NAME, arguments);
	}

	private static CallToolResult call(ApplicationContext application, String name, Map<String, Object> arguments) {
		Object specs = application.getBean("toolSpecs");
		assertThat(specs).isInstanceOf(List.class);
		SyncToolSpecification tool = ((List<?>) specs).stream().filter(SyncToolSpecification.class::isInstance)
				.map(SyncToolSpecification.class::cast).filter(spec -> spec.tool().name().equals(name))
				.findFirst().orElseThrow();
		return tool.callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
	}

	private static Map<String, Object> arguments(String alias, String symbol, Integer limit) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("accountAlias", alias);
		arguments.put("symbol", symbol);
		if (limit != null) { arguments.put("limit", limit); }
		return arguments;
	}

	private static BinanceApiException safeException(BinanceErrorCode code) throws Exception {
		var constructor = BinanceApiException.class.getDeclaredConstructor(BinanceErrorCode.class);
		constructor.setAccessible(true);
		return constructor.newInstance(code);
	}

	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		Map<String, Object> result = new LinkedHashMap<>();
		((Map<?, ?>) value).forEach((key, item) -> result.put((String) key, item));
		return result;
	}

	private static void assertMatchingSuccessContent(CallToolResult result) {
		JsonMapper mapper = JsonMapper.builder().build();
		assertThat(result.content()).hasSize(1).allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(mapper.readTree(text.text())).isEqualTo(mapper.valueToTree(result.structuredContent()))));
	}

	private void assertSafeError(CallToolResult result, String category) {
		assertThat(result.isError()).isTrue();
		assertThat(result.content()).isNotEmpty().allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(text.text()).contains("Binance request failed: " + category)));
		assertNoSecrets(result);
	}

	private static void assertSafeThrowable(Throwable failure) {
		StringWriter stack = new StringWriter();
		failure.printStackTrace(new PrintWriter(stack));
		assertThat(stack.toString()).doesNotContain(KEY, SECRET, MARKER);
		assertThat(failure.getSuppressed()).isEmpty();
	}

	private void assertNoSecrets(CallToolResult result) {
		List<String> observations = new ArrayList<>(List.of(result.content().toString(), String.valueOf(result.structuredContent())));
		for (ILoggingEvent event : logs.list) {
			observations.add(event.getFormattedMessage());
			if (event.getThrowableProxy() != null) { observations.add(ThrowableProxyUtil.asString(event.getThrowableProxy())); }
		}
		for (String observed : observations) {
			assertThat(observed).doesNotContain(KEY, SECRET, MARKER);
			for (String signature : server.signatures) { assertThat(observed).doesNotContain(signature); }
		}
	}

	static final class TransportCleanupListener extends AbstractTestExecutionListener {
		private static final String SERVER = TransportCleanupListener.class.getName() + ".server";
		@Override
		public int getOrder() { return new DirtiesContextTestExecutionListener().getOrder() - 1; }
		@Override
		public void beforeTestMethod(TestContext testContext) {
			testContext.setAttribute(SERVER, testContext.getApplicationContext().getBean(LocalBinance.class));
		}
		@Override
		public void afterTestClass(TestContext testContext) {
			LocalBinance local = (LocalBinance) testContext.removeAttribute(SERVER);
			assertThat(local).isNotNull();
			assertThat(local.transport).isNotNull();
			assertThat(local.transport.isTerminated()).isTrue();
			assertThat(local.closed).isTrue();
		}
	}

	// ContextRunner does not install SpringBootTest's test-component exclusion.
	// Scan only the production configuration package and import the tools explicitly.
	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@ComponentScan(basePackageClasses = TradeJournalProperties.class)
	@Import({TradeLedgerTools.class, UpbitOrderTools.class, BinanceAccountTradesTools.class})
	static class RunnerConfiguration {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LocalConfiguration {
		@Bean(destroyMethod = "close")
		LocalBinance localBinance() throws IOException { return new LocalBinance(); }

		@Bean(destroyMethod = "close")
		@Primary
		@Lazy
		BinanceAccountTradesClient localBinanceClient(LocalBinance local) throws ReflectiveOperationException {
			local.clientCreations.incrementAndGet();
			var constructor = BinanceAccountTradesClient.class.getDeclaredConstructor(URI.class, Supplier.class, Clock.class);
			constructor.setAccessible(true);
			BinanceAccountTradesClient client = constructor.newInstance(local.uri(), (Supplier<Map<String, String>>) local.keys::get, CLOCK);
			var transport = BinanceAccountTradesClient.class.getDeclaredField("http");
			transport.setAccessible(true);
			local.transport = (HttpClient) transport.get(client);
			return client;
		}
	}

	static final class LocalBinance implements AutoCloseable {
		final AtomicReference<Map<String, String>> keys = new AtomicReference<>(KEYS);
		final AtomicInteger status = new AtomicInteger(200);
		final AtomicReference<String> body = new AtomicReference<>("[]");
		final AtomicInteger requests = new AtomicInteger();
		final AtomicInteger clientCreations = new AtomicInteger();
		final List<String> signatures = new CopyOnWriteArrayList<>();
		final List<String> paths = new CopyOnWriteArrayList<>();
		final List<String> headers = new CopyOnWriteArrayList<>();
		final AtomicReference<CountDownLatch> releaseBody = new AtomicReference<>(new CountDownLatch(1));
		final AtomicBoolean closed = new AtomicBoolean();
		private final HttpServer http;
		private HttpClient transport;

		LocalBinance() throws IOException {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			http.createContext("/", exchange -> {
				requests.incrementAndGet();
				paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
				headers.add(exchange.getRequestHeaders().entrySet().toString());
				String signature = exchange.getRequestURI().getRawQuery().split("&signature=")[1];
				signatures.add(signature);
				try (exchange) {
					if (status.get() == -1) { return; }
					if (status.get() == -2) {
						exchange.sendResponseHeaders(200, 100);
						try { releaseBody.get().await(12, TimeUnit.SECONDS); }
						catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
						return;
					}
					String payload = body.get();
					if (!payload.stripLeading().startsWith("[")) {
						String secrets = KEY + SECRET + signature;
						payload = payload.startsWith("{")
								? payload.substring(0, payload.length() - 1) + ",\"private\":\"" + secrets + "\"}"
								: payload + secrets;
					}
					if (status.get() == 302) { exchange.getResponseHeaders().set("Location", uri().toString()); }
					byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(status.get(), bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			});
			http.start();
		}

		URI uri() { return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/api/v3/myTrades"); }

		@Override
		public void close() {
			// Spring owns the client separately. Do not close its transport here:
			// the after-context assertion must prove the client bean was destroyed.
			releaseBody.get().countDown();
			http.stop(0);
			closed.set(true);
		}
	}
}
