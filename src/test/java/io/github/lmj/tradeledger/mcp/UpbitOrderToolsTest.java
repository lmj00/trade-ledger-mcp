package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.lmj.tradeledger.TradeLedgerMcpApplication;
import io.github.lmj.tradeledger.adapter.upbit.UpbitApiException;
import io.github.lmj.tradeledger.adapter.upbit.UpbitErrorCode;
import io.github.lmj.tradeledger.adapter.upbit.UpbitOrderClient;
import io.github.lmj.tradeledger.application.model.OrderExecutionSnapshot;
import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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

@SpringBootTest(classes = {TradeLedgerMcpApplication.class, UpbitOrderToolsTest.LocalConfiguration.class},
		properties = "trade-ledger.journal.root=src/test/resources/journals")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = UpbitOrderToolsTest.TransportCleanupListener.class,
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class UpbitOrderToolsTest {

	private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
	private static final String ACCESS = "fake-mcp-access-key";
	private static final String SECRET = "fake-mcp-secret-key";
	private static final String MARKER = "private-mcp-response-marker";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = Map.of(
			"UPBIT_ACCESS_KEY", ACCESS, "UPBIT_SECRET_KEY", SECRET);
	private static final String ORDER = """
			{"uuid":"123e4567-e89b-12d3-a456-426614174000","market":"KRW-BTC","side":"bid",
			"ord_type":"limit","state":"done","created_at":"2026-07-01T10:00:00+09:00",
			"executed_volume":"0.300000000000000003","paid_fee":"0.12345678901234567800",
			"trades":[
			{"uuid":"fill-1","market":"KRW-BTC","side":"bid","price":"9007199254740993.123456789",
			"volume":"0.100000000000000001","funds":"900719925474099.321352878154740993123456789",
			"created_at":"2026-07-01T10:00:01+09:00"},
			{"uuid":"fill-2","market":"KRW-BTC","side":"bid","price":"9007199254740993.123456789",
			"volume":"0.200000000000000002","funds":"1801439850948198.642705756309481986246913578",
			"created_at":"2026-07-01T10:00:02+09:00"}]}
			""";

	@Autowired
	ApplicationContext context;

	@Autowired
	LocalUpbit server;

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Map<Logger, Level> previousLevels = new java.util.LinkedHashMap<>();

	@BeforeEach
	void resetAndCaptureApplicationLogs() {
		server.keys.set(KEYS);
		server.status.set(200);
		server.body.set(ORDER);
		server.ignoredBytes.set(null);
		server.requests.set(0);
		server.credentialReads.set(0);
		server.tokens.clear();
		server.paths.clear();
		server.headers.clear();
		startCapturingLogs();
	}

	private void startCapturingLogs() {
		logs.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
		for (String name : List.of("io.github.lmj.tradeledger", "org.springframework.ai.mcp",
				"io.modelcontextprotocol")) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			previousLevels.put(logger, logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}
	}

	@AfterEach
	void stopCapturingLogs() {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
		previousLevels.forEach(Logger::setLevel);
		logs.stop();
	}

	@Test
	void registeredHandlerPreservesFillsOrderFeeAndExactDecimals() {
		CallToolResult result = call(ID);

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> order = asMap(result.structuredContent());
		assertThat(order.get("serviceId")).isEqualTo("upbit");
		assertThat(order.get("orderId")).isEqualTo(ID);
		assertThat(order.get("createdAt")).isEqualTo("2026-07-01T01:00:00Z");
		assertThat(order.get("executedVolume")).isEqualTo("0.300000000000000003");
		assertThat(order.get("paidFee")).isEqualTo("0.12345678901234567800");
		assertThat(order).doesNotContainKey("feeCurrency");
		assertThat(order).doesNotContainKey("totalNetPnl");
		assertThat(order.get("trades")).isInstanceOf(List.class);
		List<?> trades = (List<?>) order.get("trades");
		assertThat(trades).hasSize(2);
		for (int index = 0; index < trades.size(); index++) {
			Map<String, Object> trade = asMap(trades.get(index));
			assertThat(trade.get("tradeId")).isEqualTo("fill-" + (index + 1));
			assertThat(trade.get("price")).isEqualTo("9007199254740993.123456789");
			assertThat(trade.get("volume")).isEqualTo(index == 0
					? "0.100000000000000001" : "0.200000000000000002");
			assertThat(trade.get("funds")).isEqualTo(index == 0
					? "900719925474099.321352878154740993123456789"
					: "1801439850948198.642705756309481986246913578");
			assertThat(trade).doesNotContainKey("paidFee");
		}
		assertThat(result.content()).isNotEmpty();
		assertThat(result.content().toString()).contains("9007199254740993.123456789",
				"0.12345678901234567800");
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).containsExactly("GET /v1/order?uuid=" + ID);
		assertNoSecrets(result);
	}

	@Test
	void registeredHandlerReturnsUnfilledOrderWithoutInventingTrades() {
		server.body.set(ORDER.substring(0, ORDER.indexOf("\"executed_volume\""))
				.replace("\"state\":\"done\"", "\"state\":\"wait\"")
				+ "\"executed_volume\":\"0.0000\",\"paid_fee\":\"0.00\",\"trades\":[]}");
		CallToolResult result = call(ID);
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent()).get("executedVolume")).isEqualTo("0.0000");
		assertThat(asMap(result.structuredContent()).get("paidFee")).isEqualTo("0.00");
		assertThat(asMap(result.structuredContent()).get("trades")).isEqualTo(List.of());
		assertMatchingSuccessContent(result);
	}

	private void assertMatchingSuccessContent(CallToolResult result) {
		JsonMapper mapper = JsonMapper.builder().build();
		assertThat(result.content()).hasSize(1).allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(mapper.readTree(text.text()))
								.isEqualTo(mapper.valueToTree(result.structuredContent()))));
	}

	@ParameterizedTest
	@CsvSource({"bid,done,BUY", "ask,done,SELL", "bid,cancel,BUY", "ask,cancel,SELL"})
	void commonHandlerPreservesEveryExecutionAndSingleOrderFee(String side, String state, String commonSide) {
		String alias = " 로컬 Main \uD83D\uDE00 ";
		server.body.set(ORDER.replace("\"bid\"", "\"" + side + "\"")
				.replace("\"done\"", "\"" + state + "\""));
		CallToolResult result = callExecutions(alias, ID);
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> snapshot = asMap(result.structuredContent());
		assertThat(snapshot).containsOnlyKeys("serviceId", "accountAlias", "symbol", "queryScope", "orderId",
				"possiblyTruncated", "executions", "fees")
				.containsEntry("serviceId", "upbit").containsEntry("accountAlias", alias)
				.containsEntry("symbol", "KRW-BTC").containsEntry("queryScope", "SINGLE_ORDER")
				.containsEntry("orderId", ID).containsEntry("possiblyTruncated", null);
		assertThat(snapshot.get("executions")).isInstanceOf(List.class);
		List<?> executions = (List<?>) snapshot.get("executions");
		assertThat(executions).hasSize(2);
		for (int index = 0; index < executions.size(); index++) {
			assertThat(asMap(executions.get(index))).containsOnlyKeys("serviceId", "accountAlias", "symbol",
					"executionId", "orderId", "side", "price", "quantity", "amount", "executedAt")
					.containsEntry("serviceId", "upbit").containsEntry("accountAlias", alias)
					.containsEntry("symbol", "KRW-BTC").containsEntry("executionId", "fill-" + (index + 1))
					.containsEntry("orderId", ID).containsEntry("side", commonSide)
					.containsEntry("price", "9007199254740993.123456789")
					.containsEntry("quantity", index == 0 ? "0.100000000000000001" : "0.200000000000000002")
					.containsEntry("amount", index == 0 ? "900719925474099.321352878154740993123456789"
							: "1801439850948198.642705756309481986246913578")
					.containsEntry("executedAt", "2026-07-01T01:00:0" + (index + 1) + "Z");
		}
		assertCommonFee(snapshot, "0.12345678901234567800");
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertThat(server.credentialReads).hasValue(1);
		assertThat(server.paths).containsExactly("GET /v1/order?uuid=" + ID);
		assertNoSecrets(result);
	}

	@ParameterizedTest
	@ValueSource(strings = {"wait", "cancel", "done"})
	void commonHandlerPreservesUnfilledOrderAndZeroFee(String state) {
		server.body.set(ORDER.substring(0, ORDER.indexOf("\"executed_volume\""))
				.replace("\"state\":\"done\"", "\"state\":\"" + state + "\"")
				+ "\"executed_volume\":\"0.0000\",\"paid_fee\":\"0.00\",\"trades\":[]}");
		CallToolResult result = callExecutions("main", ID);
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> snapshot = asMap(result.structuredContent());
		assertThat(snapshot).containsEntry("orderId", ID).containsEntry("queryScope", "SINGLE_ORDER")
				.containsEntry("possiblyTruncated", null).containsEntry("executions", List.of());
		assertCommonFee(snapshot, "0.00");
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertNoSecrets(result);
	}

	private static void assertCommonFee(Map<String, Object> snapshot, String amount) {
		assertThat(snapshot.get("fees")).isInstanceOf(List.class);
		List<?> fees = (List<?>) snapshot.get("fees");
		assertThat(fees).hasSize(1);
		assertThat(asMap(fees.getFirst())).containsOnlyKeys("scope", "targetId", "amount", "currency")
				.containsEntry("scope", "ORDER").containsEntry("targetId", ID)
				.containsEntry("amount", amount).containsEntry("currency", null);
	}

	@ParameterizedTest
	@MethodSource("validCommonAliases")
	void commonHandlerPreservesValidAliasesAtUtf16BoundaryWithoutSendingThem(String alias) {
		CallToolResult result = callExecutions(alias, ID);
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsEntry("accountAlias", alias);
		assertThat((List<?>) asMap(result.structuredContent()).get("executions"))
				.allSatisfy(execution -> assertThat(asMap(execution)).containsEntry("accountAlias", alias));
		assertMatchingSuccessContent(result);
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).containsExactly("GET /v1/order?uuid=" + ID);
		assertThat(server.headers).hasSize(1).allSatisfy(headers -> assertThat(headers).doesNotContain(alias));
		assertNoSecrets(result);
	}

	static Stream<String> validCommonAliases() {
		return Stream.of("x".repeat(256), "\uD83D\uDE00".repeat(128), " 로컬 Main \uD83D\uDE00 ");
	}

	static Stream<String> invalidCommonAliases() {
		return Stream.of(null, "", " \u2003 ", "x".repeat(257), "\uD83D\uDE00".repeat(128) + "x",
				MARKER + ACCESS + SECRET + "\n", "x\u0000", "x\u007f", "x\u0085",
				"x\uD800", "\uDC00x", "\uD800x", "\uD800\uD800");
	}

	@ParameterizedTest
	@MethodSource("invalidCommonAliases")
	@SuppressWarnings("unchecked")
	void commonHandlerRejectsBadAliasBeforeProviderCredentialsOrHttp(String alias) {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		assertThatThrownBy(() -> new UpbitOrderTools(provider).getUpbitOrderExecutions(alias, ID))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Upbit request failed: BAD_REQUEST").hasNoCause();
		verifyNoInteractions(provider);
		server.keys.set(Map.of());
		assertSafeError(callExecutions(alias, ID), "BAD_REQUEST");
		assertThat(server.requests).hasValue(0);
		assertThat(server.credentialReads).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "invalid", "123e4567-e89b-12d3-a456-42661417400",
			"123e4567-e89b-12d3-a456-426614174000 ", "123e4567-e89b-12d3-a456-42661417400g"})
	@SuppressWarnings("unchecked")
	void commonHandlerRejectsBadUuidBeforeProviderCredentialsOrHttp(String uuid) {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		assertThatThrownBy(() -> new UpbitOrderTools(provider).getUpbitOrderExecutions("main", uuid))
				.hasMessage("Upbit request failed: INVALID_UUID").hasNoCause();
		verifyNoInteractions(provider);
		assertSafeError(callExecutions("main", uuid), "INVALID_UUID");
		assertThat(server.requests).hasValue(0);
		assertThat(server.credentialReads).hasValue(0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void commonToolCallsExistingClientExactlyOnceAndSafelyDiscardsConversionFailure() {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		UpbitOrderClient client = mock(UpbitOrderClient.class);
		when(provider.getObject()).thenReturn(client);
		when(client.getOrder(ID)).thenReturn(new OrderExecutionSnapshot("upbit", ID, "KRW-BTC", MARKER,
				"limit", "done", java.time.Instant.EPOCH, "0.00", "0.00", List.of()));
		assertThatThrownBy(() -> new UpbitOrderTools(provider).getUpbitOrderExecutions("main", ID))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
		verify(provider).getObject();
		verify(client).getOrder(ID);
		verifyNoMoreInteractions(provider, client);
	}

	@Test
	@SuppressWarnings("unchecked")
	void commonToolProjectsOneClientResultWithoutAdditionalCalls() {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		UpbitOrderClient client = mock(UpbitOrderClient.class);
		when(provider.getObject()).thenReturn(client);
		when(client.getOrder(ID)).thenReturn(new OrderExecutionSnapshot("upbit", ID, "KRW-BTC", "bid",
				"limit", "wait", java.time.Instant.EPOCH, "0.00", "0.00", List.of()));
		CommonExecutionResponse result = new UpbitOrderTools(provider).getUpbitOrderExecutions("main", ID);
		assertThat(result.orderId()).isEqualTo(ID);
		assertThat(result.executions()).isEmpty();
		verify(provider).getObject();
		verify(client).getOrder(ID);
		verifyNoMoreInteractions(provider, client);
	}

	@Test
	@SuppressWarnings("unchecked")
	void commonToolRejectsNullUuidWithoutObtainingClient() {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		assertThatThrownBy(() -> new UpbitOrderTools(provider).getUpbitOrderExecutions("main", null))
				.hasMessage("Upbit request failed: INVALID_UUID").hasNoCause();
		verifyNoInteractions(provider);
		assertSafeError(callExecutions("main", null), "INVALID_UUID");
		assertThat(server.requests).hasValue(0);
		assertThat(server.credentialReads).hasValue(0);
	}

	@ParameterizedTest
	@CsvSource({"KRW-BTC,bid,KRW-ETH,bid", "KRW-ETH,bid,KRW-BTC,bid",
			"KRW-BTC,bid,KRW-BTC,ask", "KRW-BTC,ask,KRW-BTC,bid"})
	void bothRegisteredHandlersRejectMismatchedFillsWithoutPartialResults(
			String market, String side, String tradeMarket, String tradeSide) {
		String matching = ORDER.replace("KRW-BTC", market).replace("\"bid\"", "\"" + side + "\"");
		int second = matching.indexOf("{\"uuid\":\"fill-2\"");
		String mismatch = matching.substring(second)
				.replace("\"market\":\"" + market + "\"", "\"market\":\"" + tradeMarket + "\"")
				.replace("\"side\":\"" + side + "\"", "\"side\":\"" + tradeSide + "\"");
		String orderPrefix = matching.substring(0, matching.indexOf("\"trades\":[") + "\"trades\":[".length());
		// Include fake credentials and the actual local JWT in an ignored response field.
		server.ignoredBytes.set("valid".getBytes(StandardCharsets.UTF_8));
		int calls = 0;
		for (String body : List.of(orderPrefix + mismatch, matching.substring(0, second) + mismatch)) {
			server.body.set(body);
			for (String name : List.of("get_upbit_order", "get_upbit_order_executions")) {
				CallToolResult result = callValidTool(name);
				assertSafeError(result, "INVALID_RESPONSE");
				assertThat(result.toString()).doesNotContain(ID, "fill-1", "fill-2", "0.12345678901234567800");
				assertThat(server.requests).hasValue(++calls);
				assertThat(server.paths).hasSize(calls).allSatisfy(path ->
						assertThat(path).isEqualTo("GET /v1/order?uuid=" + ID));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"get_upbit_order", "get_upbit_order_executions"})
	void bothRegisteredHandlersEnforceTokenPolicyWithoutPartialResultsOrLeaks(String name) {
		// Add fake keys and the actual local JWT in bounded ignored fields.
		server.ignoredBytes.set("valid".getBytes(StandardCharsets.UTF_8));
		int calls = 0;
		for (int overflow : List.of(0, 1)) {
			int stringLength = 256 + overflow;
			int numberLength = 128 + overflow;
			int arrays = 63 + overflow; // Root object adds one to the depth.
			// Keep policy fields distinct from withIgnoredBytes' injected "ignored" field.
			for (String field : List.of("\"policyBoundary\":\"" + "\\u0078".repeat(stringLength) + "\"",
					"\"" + "x".repeat(stringLength) + "\":null",
					"\"policyBoundary\":1." + "0".repeat(numberLength - 6) + "e+00",
					"\"policyBoundary\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays))) {
				int end = ORDER.lastIndexOf('}');
				server.body.set(ORDER.substring(0, end) + "," + field + ORDER.substring(end));
				CallToolResult result = callValidTool(name);
				if (overflow == 0) {
					assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
					Map<String, Object> snapshot = asMap(result.structuredContent());
					assertThat((List<?>) snapshot.get(name.equals("get_upbit_order") ? "trades" : "executions"))
							.hasSize(2);
					assertMatchingSuccessContent(result);
					assertNoSecrets(result);
				}
				else {
					assertSafeError(result, "INVALID_RESPONSE");
					assertThat(result.toString()).doesNotContain(ID, "fill-1", "fill-2", "0.12345678901234567800");
				}
				assertThat(server.requests).hasValue(++calls);
				assertThat(server.credentialReads).hasValue(calls);
				assertThat(server.paths).hasSize(calls).allSatisfy(path ->
						assertThat(path).isEqualTo("GET /v1/order?uuid=" + ID));
			}
		}
	}

	@Test
	void commonHandlerDiscardsConversionDetailsWithoutAnotherQuery() {
		// The existing HTTP mapper accepts text sides; the common model rejects unknown sides.
		server.body.set(ORDER.replace("\"bid\"", "\"" + MARKER + "\""));
		assertSafeError(callExecutions("main", ID), "INTERNAL_ERROR");
		assertThat(server.requests).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {1000, 1001})
	void commonHandlerKeepsTheExistingFillBoundWithoutExtraQueries(int count) {
		String fills = java.util.stream.IntStream.range(0, count).mapToObj(index ->
				"{\"uuid\":\"fill-" + index + "\",\"market\":\"KRW-BTC\",\"side\":\"bid\","
						+ "\"price\":\"1.2300\",\"volume\":\"0.0200\",\"funds\":\"0.024600\","
						+ "\"created_at\":\"2026-07-01T10:00:01+09:00\"}")
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		server.body.set(ORDER.substring(0, ORDER.indexOf("\"trades\"")) + "\"trades\":" + fills + "}");
		CallToolResult result = callExecutions("main", ID);
		if (count == 1001) {
			assertSafeError(result, "INVALID_RESPONSE");
		}
		else {
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			Map<String, Object> snapshot = asMap(result.structuredContent());
			assertThat((List<?>) snapshot.get("executions")).hasSize(count).allSatisfy(execution ->
					assertThat(asMap(execution)).containsEntry("price", "1.2300")
							.containsEntry("quantity", "0.0200").containsEntry("amount", "0.024600"));
			assertCommonFee(snapshot, "0.12345678901234567800");
			assertMatchingSuccessContent(result);
		}
		assertThat(server.requests).hasValue(1);
		assertNoSecrets(result);
	}

	@Test
	void commonHandlerRejectsMissingKeysWithoutHttp() {
		server.keys.set(Map.of());
		assertSafeError(callExecutions("main", ID), "MISSING_CREDENTIALS");
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"get_upbit_order", "get_upbit_order_executions", "get_upbit_closed_orders"})
	void registeredHandlersRejectMalformedUtf8InIgnoredFieldsWithoutPartialResults(String name) {
		server.body.set(utf8SuccessBody(name));
		// The same fields and secrets must pass when only the injected bytes are valid.
		server.ignoredBytes.set("valid".getBytes(StandardCharsets.UTF_8));
		CallToolResult control = callValidTool(name);
		assertThat(control.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> snapshot = asMap(control.structuredContent());
		assertThat(snapshot).containsEntry("serviceId", "upbit");
		if (name.equals("get_upbit_closed_orders")) {
			assertThat((List<?>) snapshot.get("orders")).hasSize(2);
		}
		else {
			assertThat(snapshot).containsEntry("orderId", ID);
		}
		assertMatchingSuccessContent(control);
		assertNoSecrets(control);
		assertThat(server.requests).hasValue(1);
		assertThat(server.credentialReads).hasValue(1);
		int calls = 1;
		for (byte[] bytes : malformedUtf8()) {
			server.ignoredBytes.set(bytes);
			CallToolResult result = callValidTool(name);
			assertSafeError(result, "INVALID_RESPONSE");
			assertThat(result.toString()).doesNotContain(ID, "123e4567-e89b-12d3-a456-426614174001",
					"fill-1", "fill-2", "0.12345678901234567800");
			assertThat(server.requests).hasValue(++calls);
			assertThat(server.credentialReads).hasValue(calls);
			assertThat(server.paths).hasSize(calls).allSatisfy(path -> assertThat(path).isEqualTo(
					name.equals("get_upbit_closed_orders")
							? "GET /v1/orders/closed?states%5B%5D=done&states%5B%5D=cancel&limit=100&order_by=desc"
							: "GET /v1/order?uuid=" + ID));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"get_upbit_order", "get_upbit_order_executions", "get_upbit_closed_orders"})
	void registeredHandlersAcceptValidNonAsciiAndEncodedReplacementCharacter(String name) {
		server.body.set(utf8SuccessBody(name));
		int calls = 0;
		for (String text : List.of("한글 \uD83D\uDE00", "\uFFFD")) {
			server.ignoredBytes.set(text.getBytes(StandardCharsets.UTF_8));
			CallToolResult result = callValidTool(name);
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			Map<String, Object> snapshot = asMap(result.structuredContent());
			assertThat(snapshot).containsEntry("serviceId", "upbit");
			if (name.equals("get_upbit_closed_orders")) {
				assertThat((List<?>) snapshot.get("orders")).hasSize(2);
			}
			else {
				assertThat(snapshot).containsEntry("orderId", ID);
			}
			assertMatchingSuccessContent(result);
			assertNoSecrets(result);
			assertThat(server.requests).hasValue(++calls);
			assertThat(server.credentialReads).hasValue(calls);
		}
	}

	@ParameterizedTest
	@CsvSource({"400,BAD_REQUEST", "401,AUTHENTICATION_FAILED", "403,PERMISSION_DENIED",
			"404,ORDER_NOT_FOUND", "418,IP_BLOCKED", "429,RATE_LIMITED", "302,REDIRECT_REJECTED",
			"307,REDIRECT_REJECTED", "500,SERVER_ERROR", "503,SERVER_ERROR", "599,SERVER_ERROR"})
	void registeredHandlersKeepHttpCategoriesForMalformedUtf8ErrorBodies(int status, String category) {
		server.status.set(status);
		server.body.set("{\"error\":{\"name\":\"out_of_scope\",\"message\":\"" + MARKER + "\"}}");
		int calls = 0;
		for (byte[] bytes : malformedUtf8()) {
			server.ignoredBytes.set(bytes);
			for (String name : List.of("get_upbit_order", "get_upbit_order_executions", "get_upbit_closed_orders")) {
				assertSafeError(callValidTool(name), category);
				assertThat(server.requests).hasValue(++calls);
				assertThat(server.credentialReads).hasValue(calls);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	void registeredHandlersKeepPermissionCategoryForValidUtf8ErrorBodies(int status) {
		server.status.set(status);
		server.body.set("{\"error\":{\"name\":\"out_of_scope\",\"message\":\"권한 \uFFFD\"}}");
		int calls = 0;
		for (String name : List.of("get_upbit_order", "get_upbit_order_executions", "get_upbit_closed_orders")) {
			assertSafeError(callValidTool(name), "PERMISSION_DENIED");
			assertThat(server.requests).hasValue(++calls);
		}
	}

	private CallToolResult callValidTool(String name) {
		return switch (name) {
			case "get_upbit_order" -> call(ID);
			case "get_upbit_order_executions" -> callExecutions("main", ID);
			case "get_upbit_closed_orders" -> callClosed(Map.of());
			default -> throw new AssertionError("Unknown test tool");
		};
	}

	private static String utf8SuccessBody(String name) {
		return name.equals("get_upbit_closed_orders")
				? "[" + summary(ID, "done", "2", "0.3000") + ","
						+ summary("123e4567-e89b-12d3-a456-426614174001", "cancel", "0", "0") + "]"
				: ORDER;
	}

	private static List<byte[]> malformedUtf8() {
		return List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xC3, 0x28},
				new byte[] {(byte) 0xC0, (byte) 0xAF}, new byte[] {(byte) 0xE2, (byte) 0x82});
	}

	private static byte[] withIgnoredBytes(String json, byte[] value, String secrets) {
		// Damage the second closed order after a valid first result; otherwise use the root object.
		int start = (json.startsWith("[") ? json.lastIndexOf("{\"uuid\":") : json.indexOf('{')) + 1;
		// Keep each ignored string below the closed-order mapper's existing length limit.
		StringBuilder fields = new StringBuilder();
		JsonMapper jsonMapper = JsonMapper.builder().build();
		for (int offset = 0; offset < secrets.length(); offset += 128) {
			fields.append("\"private").append(offset / 128).append("\":")
					.append(jsonMapper.writeValueAsString(
							secrets.substring(offset, Math.min(offset + 128, secrets.length())))).append(',');
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes((json.substring(0, start) + "\"ignored\":\"").getBytes(StandardCharsets.UTF_8));
		bytes.writeBytes(value);
		bytes.writeBytes(("\"," + fields + json.substring(start)).getBytes(StandardCharsets.UTF_8));
		return bytes.toByteArray();
	}

	@ParameterizedTest
	@CsvSource({"400,BAD_REQUEST", "401,AUTHENTICATION_FAILED", "403,PERMISSION_DENIED",
			"404,ORDER_NOT_FOUND", "418,IP_BLOCKED", "429,RATE_LIMITED", "500,SERVER_ERROR",
			"302,REDIRECT_REJECTED", "200,INVALID_RESPONSE", "-1,NETWORK_ERROR"})
	void commonHandlerReusesSafeHttpParserAndNetworkErrorsWithoutRetry(int status, String category) {
		server.status.set(status);
		server.body.set(MARKER);
		assertSafeError(callExecutions("main", ID), category);
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void registeredHandlerRejectsMissingKeysAndInvalidUuidBeforeHttp() {
		server.keys.set(Map.of());
		assertSafeError(call(ID), "MISSING_CREDENTIALS");
		assertSafeError(call(MARKER + ACCESS + SECRET), "INVALID_UUID");
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@CsvSource({"400,BAD_REQUEST", "401,AUTHENTICATION_FAILED", "403,PERMISSION_DENIED",
			"404,ORDER_NOT_FOUND", "418,IP_BLOCKED", "429,RATE_LIMITED", "500,SERVER_ERROR",
			"302,REDIRECT_REJECTED", "200,INVALID_RESPONSE"})
	void registeredHandlerDiscardsRawHttpAndParserErrors(int status, String category) {
		server.status.set(status);
		server.body.set(MARKER); // The local server also echoes keys and the received JWT.
		assertSafeError(call(ID), category);
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void registeredHandlerRecognizesPermissionCategoryWithinAuthenticationResponse() {
		server.status.set(401);
		server.body.set("{\"error\":{\"name\":\"out_of_scope\",\"message\":\"" + MARKER + "\"}}");
		assertSafeError(call(ID), "PERMISSION_DENIED");
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void registeredHandlerDiscardsNetworkErrorsWithoutRetrying() {
		server.status.set(-1); // Drop the connection before sending response headers.
		assertSafeError(call(ID), "NETWORK_ERROR");
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void closedOrdersDefaultCallMakesOneGetAndPreservesEmptyArray() {
		server.body.set("[]");
		CallToolResult result = callClosed(Map.of());
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsOnlyKeys("serviceId", "orders", "possiblyTruncated")
				.containsEntry("serviceId", "upbit").containsEntry("orders", List.of())
				.containsEntry("possiblyTruncated", false);
		assertThat(server.paths).containsExactly(
				"GET /v1/orders/closed?states%5B%5D=done&states%5B%5D=cancel&limit=100&order_by=desc");
		assertThat(server.requests).hasValue(1);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@Test
	void closedOrdersPassesOptionsAndNormalizesAnExactlySevenDayInterval() {
		server.body.set("[]");
		CallToolResult result = callClosed(Map.of("market", "KRW-BTC", "state", "cancel",
				"startTime", "2026-07-01T10:00:00+09:00", "endTime", "1783472400000",
				"limit", 1000, "orderBy", "asc"));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(server.paths).containsExactly("GET /v1/orders/closed?market=KRW-BTC&state=cancel"
				+ "&start_time=2026-07-01T01%3A00%3A00Z&end_time=2026-07-08T01%3A00%3A00Z"
				+ "&limit=1000&order_by=asc");
		assertThat(server.requests).hasValue(1);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@Test
	void closedOrdersPassesStatesWithoutAddingDefaultState() {
		server.body.set("[]");
		CallToolResult result = callClosed(Map.of("states", List.of("cancel")));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(server.paths).containsExactly(
				"GET /v1/orders/closed?states%5B%5D=cancel&limit=100&order_by=desc");
		assertThat(server.requests).hasValue(1);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@ParameterizedTest
	@MethodSource("invalidClosedRequests")
	@SuppressWarnings("unchecked")
	void closedOrdersRejectsInvalidRequestsWithoutHttpEvenWhenKeysAreMissing(Map<String, Object> arguments) {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		assertThatThrownBy(() -> new UpbitOrderTools(provider).getUpbitClosedOrders(
				(String) arguments.get("market"), (String) arguments.get("state"),
				(List<String>) arguments.get("states"), (String) arguments.get("startTime"),
				(String) arguments.get("endTime"), (Integer) arguments.get("limit"),
				(String) arguments.get("orderBy")))
				.hasMessage("Upbit request failed: BAD_REQUEST").hasNoCause();
		verifyNoInteractions(provider);
		server.keys.set(Map.of());
		assertSafeError(callClosed(arguments), "BAD_REQUEST");
		assertThat(server.requests).hasValue(0);
	}

	static Stream<Map<String, Object>> invalidClosedRequests() {
		return Stream.of(Map.of("state", "done", "states", List.of("cancel")),
				Map.of("state", "wait"), Map.of("states", List.of("done", "wait")),
				Map.of("states", List.of()), Map.of("states", List.of("done", "done")),
				Map.of("market", MARKER + ACCESS + SECRET), Map.of("market", "krw-btc"),
				Map.of("orderBy", MARKER + ACCESS + SECRET), Map.of("limit", 0), Map.of("limit", 1001),
				Map.of("startTime", "2026-07-01T00:00:00Z"), Map.of("endTime", "2026-07-02T00:00:00Z"),
				Map.of("startTime", "2026-07-02T00:00:00Z", "endTime", "2026-07-01T00:00:00Z"),
				Map.of("startTime", "2026-07-01T00:00:00Z", "endTime", "2026-07-08T00:00:00.001Z"),
				Map.of("startTime", MARKER + ACCESS + SECRET, "endTime", "2026-07-08T00:00:00Z"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void validationPrecedesLazyClientCreationAndDropsInputAndCauses() {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		UpbitOrderTools tools = new UpbitOrderTools(provider);
		assertThatThrownBy(() -> tools.getUpbitClosedOrders(MARKER + ACCESS + SECRET,
				null, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Upbit request failed: BAD_REQUEST").hasNoCause();
		assertThatThrownBy(() -> tools.getUpbitOrder(MARKER + ACCESS + SECRET))
				.hasMessage("Upbit request failed: INVALID_UUID").hasNoCause();
		assertThatThrownBy(() -> tools.getUpbitOrderExecutions("", MARKER + ACCESS + SECRET))
				.hasMessage("Upbit request failed: INVALID_UUID").hasNoCause();
		verifyNoInteractions(provider);
	}

	@Test
	@SuppressWarnings("unchecked")
	void lazyInitializationFailuresExposeOnlyExistingSafeCategories() throws ReflectiveOperationException {
		ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
		UpbitOrderTools tools = new UpbitOrderTools(provider);
		var constructor = UpbitApiException.class.getDeclaredConstructor(UpbitErrorCode.class);
		constructor.setAccessible(true);
		for (UpbitErrorCode code : List.of(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION,
				UpbitErrorCode.MISSING_CREDENTIALS)) {
			doThrow(new BeanCreationException(MARKER + ACCESS + SECRET,
					constructor.newInstance(code))).when(provider).getObject();
			assertThatThrownBy(() -> tools.getUpbitClosedOrders(null, null, null, null, null, null, null))
					.hasMessage("Upbit request failed: " + code.name()).hasNoCause();
			assertThatThrownBy(() -> tools.getUpbitOrder(ID))
					.hasMessage("Upbit request failed: " + code.name()).hasNoCause();
			assertThatThrownBy(() -> tools.getUpbitOrderExecutions("main", ID))
					.hasMessage("Upbit request failed: " + code.name()).hasNoCause();
		}
		doThrow(new IllegalStateException(MARKER + ACCESS + SECRET)).when(provider).getObject();
		assertThatThrownBy(() -> tools.getUpbitClosedOrders(null, null, null, null, null, null, null))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
		assertThatThrownBy(() -> tools.getUpbitOrder(ID))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
		assertThatThrownBy(() -> tools.getUpbitOrderExecutions("main", ID))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
	}

	@ParameterizedTest
	@ValueSource(strings = {"get_upbit_order", "get_upbit_order_executions", "get_upbit_closed_orders"})
	@SuppressWarnings("unchecked")
	void initializationCauseGraphsTerminateAndKeepSafeCategories(String name) {
		String methodName = switch (name) {
			case "get_upbit_order" -> "getUpbitOrder";
			case "get_upbit_order_executions" -> "getUpbitOrderExecutions";
			case "get_upbit_closed_orders" -> "getUpbitClosedOrders";
			default -> throw new AssertionError("Unknown test tool");
		};
		for (String shape : List.of("cycle", "safeCycle", "chain", "safeChain")) {
			for (boolean registered : List.of(false, true)) {
				BoundedCause first = new BoundedCause();
				BoundedCause second = new BoundedCause();
				first.addSuppressed(new IllegalStateException(MARKER + ACCESS + SECRET));
				first.next = second;
				boolean safe = shape.startsWith("safe");
				boolean cycle = shape.endsWith("Cycle") || shape.equals("cycle");
				if (safe) {
					UpbitApiException category = mock(UpbitApiException.class);
					when(category.code()).thenReturn(UpbitErrorCode.MISSING_CREDENTIALS);
					when(category.getCause()).thenReturn(cycle ? first : null);
					second.next = category;
				}
				else {
					second.next = cycle ? first : null;
				}
				String category = safe ? "MISSING_CREDENTIALS" : "INTERNAL_ERROR";
				ObjectProvider<UpbitOrderClient> provider = mock(ObjectProvider.class);
				doThrow(first).when(provider).getObject();
				UpbitOrderTools tools = new UpbitOrderTools(provider);
				if (registered) {
					AtomicReference<CallToolResult> observed = new AtomicReference<>();
					new ApplicationContextRunner().withUserConfiguration(FailureConfiguration.class)
							.withBean(UpbitOrderTools.class, () -> tools)
							.withPropertyValues("spring.ai.mcp.server.type=SYNC",
									"spring.ai.mcp.server.stdio=true")
							.run(application -> {
								assertThat(application).hasNotFailed();
								CallToolResult result = call(application, name, validArguments(name));
								observed.set(result);
								assertSafeError(result, category);
								assertThat(result.content()).hasSize(1).allSatisfy(content ->
										assertThat(((TextContent) content).text())
												.isEqualTo("Error invoking method: " + methodName
														+ "\nUpbit request failed: " + category));
							});
					// Include context shutdown in the collected-log checks.
					assertNoSecrets(observed.get());
					assertNoFailureDetails(observed.get());
				}
				else {
					assertThatThrownBy(() -> invokeValidTool(tools, name))
							.isInstanceOf(IllegalStateException.class)
							.hasMessage("Upbit request failed: " + category).hasNoCause()
							.satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
				}
				assertThat(first.reads).isEqualTo(1);
				assertThat(second.reads).isEqualTo(1);
				verify(provider).getObject();
				verifyNoMoreInteractions(provider);
				assertThat(server.requests).hasValue(0);
				assertThat(server.credentialReads).hasValue(0);
			}
		}
	}

	private static Map<String, Object> validArguments(String name) {
		return switch (name) {
			case "get_upbit_order" -> Map.of("uuid", ID);
			case "get_upbit_order_executions" -> Map.of("uuid", ID, "accountAlias", "main");
			case "get_upbit_closed_orders" -> Map.of();
			default -> throw new AssertionError("Unknown test tool");
		};
	}

	private static void invokeValidTool(UpbitOrderTools tools, String name) {
		switch (name) {
			case "get_upbit_order" -> tools.getUpbitOrder(ID);
			case "get_upbit_order_executions" -> tools.getUpbitOrderExecutions("main", ID);
			case "get_upbit_closed_orders" ->
					tools.getUpbitClosedOrders(null, null, null, null, null, null, null);
			default -> throw new AssertionError("Unknown test tool");
		}
	}

	private void assertNoFailureDetails(CallToolResult result) {
		List<String> observations = new ArrayList<>(List.of(result.toString()));
		for (ILoggingEvent event : logs.list) {
			observations.add(event.getFormattedMessage());
			if (event.getThrowableProxy() != null) {
				observations.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
			}
		}
		for (String observation : observations) {
			assertThat(observation).doesNotContain(ACCESS, SECRET, MARKER, "raw-initialization-failure",
					"BoundedCause", "Caused by:", "\tat ");
		}
	}

	// Equal values deliberately represent distinct objects: traversal must use identity.
	// A broken traversal fails after bounded reads instead of hanging the test worker.
	private static final class BoundedCause extends RuntimeException {
		private Throwable next;
		private int reads;

		BoundedCause() {
			super("raw-initialization-failure " + MARKER + ACCESS + SECRET);
		}

		@Override
		public synchronized Throwable getCause() {
			if (++reads > 8) {
				throw new AssertionError("Cause traversal exceeded its test bound");
			}
			return next;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof BoundedCause;
		}

		@Override
		public int hashCode() {
			return 1;
		}
	}

	@Test
	void closedOrdersPreservesDoneAndFilledAndUnfilledCancelSummariesWithExactCounts() {
		server.body.set("[" + summary(ID, "done", "9007199254740993", "0.3000") + ","
				+ summary("123e4567-e89b-12d3-a456-426614174001", "cancel", "9223372036854775808", "0.1000")
				+ "," + summary("123e4567-e89b-12d3-a456-426614174002", "cancel", "0", "0.0000") + "]");
		CallToolResult result = callClosed(Map.of());
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		Map<String, Object> response = asMap(result.structuredContent());
		assertThat(response).containsEntry("serviceId", "upbit").containsEntry("possiblyTruncated", false);
		assertThat(response.get("orders")).isInstanceOf(List.class);
		List<?> orders = (List<?>) response.get("orders");
		assertThat(orders).hasSize(3);
		for (int index = 0; index < orders.size(); index++) {
			assertThat(asMap(orders.get(index)))
					.containsOnlyKeys("orderId", "market", "side", "orderType", "state", "createdAt",
							"executedVolume", "paidFee", "tradesCount")
					.containsEntry("orderId", "123e4567-e89b-12d3-a456-42661417400" + index)
					.containsEntry("market", "KRW-BTC").containsEntry("side", "bid")
					.containsEntry("orderType", "limit").containsEntry("state", index == 0 ? "done" : "cancel")
					.containsEntry("createdAt", "2026-07-01T01:00:00Z")
					.containsEntry("executedVolume", List.of("0.3000", "0.1000", "0.0000").get(index))
					.containsEntry("paidFee", "0.12345678901234567800")
					.containsEntry("tradesCount", List.of("9007199254740993", "9223372036854775808", "0").get(index));
		}
		assertThat(server.requests).hasValue(1);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 1000})
	void closedOrdersMarksResultsAtEitherAllowedLimitAsPossiblyTruncated(int limit) {
		String body = java.util.stream.IntStream.range(0, limit)
				.mapToObj(index -> summary("123e4567-e89b-12d3-a456-" + String.format(java.util.Locale.ROOT, "%012d", index),
						"done", "1", "0.1000")).collect(java.util.stream.Collectors.joining(",", "[", "]"));
		server.body.set(body);
		CallToolResult result = callClosed(Map.of("limit", limit));
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(asMap(result.structuredContent())).containsEntry("possiblyTruncated", true);
		assertThat((List<?>) asMap(result.structuredContent()).get("orders")).hasSize(limit);
		assertThat(server.requests).hasValue(1);
		assertMatchingSuccessContent(result);
		assertNoSecrets(result);
	}

	@Test
	void closedOrdersRejectsRequestedScopeMismatchesThroughRegisteredHandler() {
		record Scope(Map<String, Object> arguments, String matching, String mismatch) { }
		String done = summary(ID, "done", "2", "0.3000");
		List<Scope> scopes = new ArrayList<>();
		scopes.add(new Scope(Map.of("market", "KRW-BTC"), done, done.replace("KRW-BTC", "KRW-ETH")));
		for (String state : List.of("done", "cancel")) {
			String matching = summary(ID, state, "2", "0.3000");
			String mismatch = summary(ID, state.equals("done") ? "cancel" : "done", "2", "0.3000");
			scopes.add(new Scope(Map.of("state", state), matching, mismatch));
			scopes.add(new Scope(Map.of("states", List.of(state)), matching, mismatch));
		}
		// Include fake keys, the actual request JWT and private text in ignored fields.
		server.ignoredBytes.set("safe".getBytes(StandardCharsets.UTF_8));
		int calls = 0;
		for (Scope scope : scopes) {
			server.body.set("[" + scope.matching() + "]");
			CallToolResult accepted = callClosed(scope.arguments());
			assertThat(accepted.isError()).isNotEqualTo(Boolean.TRUE);
			List<?> orders = (List<?>) asMap(accepted.structuredContent()).get("orders");
			assertThat(orders).hasSize(1);
			assertThat(asMap(orders.getFirst())).containsEntry("market", "KRW-BTC")
					.containsEntry("executedVolume", "0.3000");
			assertMatchingSuccessContent(accepted);
			assertNoSecrets(accepted);
			assertThat(server.requests).hasValue(++calls);
			String later = scope.mismatch().replace(ID, "123e4567-e89b-12d3-a456-426614174001");
			for (String json : List.of("[" + later + "]", "[" + scope.matching() + "," + later + "]")) {
				server.body.set(json);
				CallToolResult rejected = callClosed(scope.arguments());
				assertSafeError(rejected, "INVALID_RESPONSE");
				assertThat(rejected.content().toString()).doesNotContain("Exception", "Caused by:", "\tat ");
				assertThat(server.requests).hasValue(++calls);
			}
		}
		assertThat(server.paths).hasSize(calls)
				.allSatisfy(target -> assertThat(target).startsWith("GET /v1/orders/closed?"));
	}

	@Test
	void closedOrdersAllowsMixedMarketsAndBothStatesForDefaultAndExplicitSelections() {
		server.body.set("[" + summary(ID, "done", "2", "0.3000") + ","
				+ summary("123e4567-e89b-12d3-a456-426614174001", "cancel", "1", "0.1000")
						.replace("KRW-BTC", "KRW-ETH") + "]");
		server.ignoredBytes.set("safe".getBytes(StandardCharsets.UTF_8));
		int calls = 0;
		for (Map<String, Object> arguments : List.of(Map.<String, Object>of(),
				Map.<String, Object>of("states", List.of("done", "cancel")),
				Map.<String, Object>of("states", List.of("cancel", "done")))) {
			CallToolResult result = callClosed(arguments);
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
			List<?> orders = (List<?>) asMap(result.structuredContent()).get("orders");
			assertThat(orders).hasSize(2);
			assertThat(asMap(orders.getFirst())).containsEntry("market", "KRW-BTC").containsEntry("state", "done");
			assertThat(asMap(orders.get(1))).containsEntry("market", "KRW-ETH").containsEntry("state", "cancel")
					.containsEntry("executedVolume", "0.1000");
			assertMatchingSuccessContent(result);
			assertNoSecrets(result);
			assertThat(server.requests).hasValue(++calls);
		}
		assertThat(server.paths).hasSize(calls)
				.allSatisfy(target -> assertThat(target).startsWith("GET /v1/orders/closed?"));
	}

	@Test
	void closedOrdersRejectsResponseBeyondRequestedLimitWithoutMoreRequests() {
		server.body.set("[" + summary(ID, "done", "1", "0.1000") + ","
				+ summary("123e4567-e89b-12d3-a456-426614174001", "cancel", "0", "0.0000") + "]");
		assertSafeError(callClosed(Map.of("limit", 1)), "INVALID_RESPONSE");
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void closedOrdersRejectsMissingKeysWithoutHttp() {
		server.keys.set(Map.of());
		assertSafeError(callClosed(Map.of()), "MISSING_CREDENTIALS");
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@CsvSource({"400,BAD_REQUEST", "401,AUTHENTICATION_FAILED", "403,PERMISSION_DENIED",
			"404,ORDER_NOT_FOUND", "418,IP_BLOCKED", "429,RATE_LIMITED", "500,SERVER_ERROR",
			"302,REDIRECT_REJECTED", "200,INVALID_RESPONSE", "-1,NETWORK_ERROR"})
	void closedOrdersDiscardsHttpParserAndNetworkDetailsWithoutRetrying(int status, String category) {
		server.status.set(status);
		server.body.set(MARKER);
		assertSafeError(callClosed(Map.of()), category);
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void closedOrdersRecognizesPermissionWithinAuthenticationResponse() {
		server.status.set(401);
		server.body.set("{\"error\":{\"name\":\"out_of_scope\",\"message\":\"" + MARKER + "\"}}");
		assertSafeError(callClosed(Map.of()), "PERMISSION_DENIED");
		assertThat(server.requests).hasValue(1);
	}

	@Test
	void closedOrdersBoundsFullBodyWaitAndDoesNotRetry() {
		server.status.set(-2); // Send headers, then hold the body until the request has timed out.
		try {
			assertSafeError(callClosed(Map.of()), "TIMEOUT");
			assertThat(server.requests).hasValue(1);
		}
		finally {
			server.releaseBody.countDown();
		}
	}

	private static String summary(String uuid, String state, String count, String volume) {
		return "{\"uuid\":\"" + uuid + "\",\"market\":\"KRW-BTC\",\"side\":\"bid\","
				+ "\"ord_type\":\"limit\",\"state\":\"" + state + "\",\"created_at\":\"2026-07-01T10:00:00+09:00\","
				+ "\"executed_volume\":\"" + volume + "\",\"paid_fee\":\"0.12345678901234567800\","
				+ "\"trades_count\":" + count + "}";
	}

	private CallToolResult callClosed(Map<String, Object> arguments) {
		return call("get_upbit_closed_orders", arguments);
	}

	private CallToolResult callExecutions(String accountAlias, String uuid) {
		Map<String, Object> arguments = new java.util.LinkedHashMap<>();
		arguments.put("accountAlias", accountAlias);
		arguments.put("uuid", uuid);
		return call("get_upbit_order_executions", arguments);
	}

	private CallToolResult call(String uuid) {
		return call("get_upbit_order", Map.of("uuid", uuid));
	}

	private CallToolResult call(String name, Map<String, Object> arguments) {
		return call(context, name, arguments);
	}

	private static CallToolResult call(ApplicationContext application, String name, Map<String, Object> arguments) {
		Object specs = application.getBean("toolSpecs");
		assertThat(specs).isInstanceOf(List.class);
		SyncToolSpecification tool = ((List<?>) specs).stream()
				.filter(SyncToolSpecification.class::isInstance).map(SyncToolSpecification.class::cast)
				.filter(specification -> specification.tool().name().equals(name))
				.findFirst().orElseThrow();
		return tool.callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
	}

	@Test
	void independentJvmsKeepBothRegisteredHandlersSafeAndCalculationToolsLazy() throws Exception {
		List<String> valid = List.of("-Djdk.httpclient.disableRetryConnect=true",
				"-Djdk.httpclient.redirects.retrylimit=1");
		assertThat(runLoggingProbe(List.of(), "missing")).isEqualTo("PROBE_OK\n");
		List<String> logging = new ArrayList<>(valid);
		logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
		assertThat(runLoggingProbe(logging, "logging")).isEqualTo("PROBE_OK\n");
		assertThat(runLoggingProbe(logging, "clearedLogging")).isEqualTo("PROBE_OK\n");
		logging.add("-D" + HTTP_LOG + "=");
		assertThat(runLoggingProbe(logging, "logging")).isEqualTo("PROBE_OK\n");
		assertThat(runLoggingProbe(valid, "lateLogging")).isEqualTo("PROBE_OK\n");
	}

	private static String runLoggingProbe(List<String> options, String mode) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).isNotBlank();
		List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, LoggingProbe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
				"UPBIT_ACCESS_KEY", "UPBIT_SECRET_KEY", "BINANCE_API_KEY", "BINANCE_SECRET_KEY")) {
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
					assertThat(output.size()).as("Child JVM output bytes").isLessThanOrEqualTo(8192);
				}
				if (exited) { break; }
				assertThat(System.nanoTime() < deadline).as("Child JVM must finish within 20 seconds").isTrue();
				exited = process.waitFor(25, TimeUnit.MILLISECONDS);
			}
			String observed = output.toString(StandardCharsets.UTF_8);
			assertThat(observed).doesNotContain(ACCESS, SECRET, MARKER, "Bearer ", "eyJ");
			assertThat(process.exitValue()).as("Child JVM exit status; bounded output: %s", observed).isZero();
			return observed;
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Child JVM cleanup").isTrue();
			}
		}
	}

	/** Isolated JVM, fake credentials and loopback only, including registered MCP handlers. */
	public static final class LoggingProbe {
		public static void main(String[] args) {
			UpbitOrderToolsTest fixture = new UpbitOrderToolsTest();
			AtomicReference<CallToolResult> lastResult = new AtomicReference<>();
			try {
				// Capture application logs in memory; stdout/stderr (including JDK logs)
				// remain captured by the parent process with its separate 8192-byte bound.
				((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAndStopAllAppenders();
				fixture.startCapturingLogs();
				new ApplicationContextRunner()
						.withUserConfiguration(ProbeConfiguration.class, ProbeLocalConfiguration.class)
						.withPropertyValues("trade-ledger.journal.root=src/test/resources/journals",
								"spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=true",
								"logging.console.enabled=false", "spring.main.banner-mode=off")
						.run(application -> {
							assertThat(application).hasNotFailed();
							assertThat(application).doesNotHaveBean("localBinanceClient");
							fixture.context = application;
							fixture.server = application.getBean(LocalUpbit.class);
							LocalUpbit local = fixture.server;
							if (args[0].equals("missing")) { local.keys.set(Map.of()); }
							assertClientsAreLazy(application);
							CallToolResult summary = fixture.call("summarize_journal", Map.of("journalName", "sample-trades"));
							assertThat(summary.isError()).isNotEqualTo(Boolean.TRUE);
							assertThat(asMap(summary.structuredContent())).containsEntry("totalNetPnl", "355.78645");
							Map<String, Object> trade = Map.ofEntries(
									Map.entry("tradeId", "probe-trade"), Map.entry("symbol", "BTC-USDT"),
									Map.entry("market", "CRYPTO"), Map.entry("direction", "LONG"),
									Map.entry("quantity", "1"), Map.entry("entryPrice", "100"),
									Map.entry("exitPrice", "110"), Map.entry("fees", "1"),
									Map.entry("currency", "USDT"), Map.entry("openedAt", "2026-07-01T00:00:00Z"),
									Map.entry("closedAt", "2026-07-01T01:00:00Z"), Map.entry("strategy", "probe"));
							CallToolResult analysis = fixture.call("analyze_trades", Map.of("trades", List.of(trade)));
							assertThat(analysis.isError()).isNotEqualTo(Boolean.TRUE);
							assertThat(asMap(analysis.structuredContent())).containsEntry("totalNetPnl", "9");
							assertClientsAreLazy(application);
							if (args[0].equals("clearedLogging")) { System.clearProperty(HTTP_LOG); }
							if (args[0].equals("lateLogging")) {
								application.getBean("localUpbitClient", UpbitOrderClient.class);
								assertThat(local.clientCreations).hasValue(1);
								assertThat(local.credentialReads).hasValue(0);
								System.setProperty(HTTP_LOG, "requests,headers,content,errors");
							}
							int creations = local.clientCreations.get();
							fixture.assertSafeError(fixture.call(MARKER + ACCESS + SECRET), "INVALID_UUID");
							fixture.assertSafeError(fixture.callExecutions("main", MARKER + ACCESS + SECRET), "INVALID_UUID");
							for (String alias : invalidCommonAliases().toList()) {
								fixture.assertSafeError(fixture.callExecutions(alias, ID), "BAD_REQUEST");
							}
							for (Map<String, Object> invalid : invalidClosedRequests().toList()) {
								fixture.assertSafeError(fixture.callClosed(invalid), "BAD_REQUEST");
							}
							assertThat(local.clientCreations).hasValue(creations);
							local.body.set(MARKER); // An accidental send would echo fake keys and the JWT too.
							for (CallToolResult result : List.of(fixture.call(ID), fixture.callClosed(Map.of()),
									fixture.callExecutions("main", ID))) {
								fixture.assertSafeError(result, "UNSAFE_TRANSPORT_CONFIGURATION");
								assertThat(result.content().toString()).doesNotContain("Exception", "Caused by:", "\tat ");
								lastResult.set(result);
							}
							assertThat(local.credentialReads).hasValue(0);
							assertThat(local.requests).hasValue(0);
							assertThat(local.tokens).isEmpty();
							// Check the real lazy-construction/current-setting failures for cause/stack leaks too.
							UpbitOrderTools tools = application.getBean(UpbitOrderTools.class);
							assertSafeFailure(() -> tools.getUpbitOrder(ID));
							assertSafeFailure(() -> tools.getUpbitOrderExecutions("main", ID));
							assertSafeFailure(() -> tools.getUpbitClosedOrders(null, null, null, null, null, null, null));
							assertThat(local.credentialReads).hasValue(0);
							assertThat(local.requests).hasValue(0);
						});
				assertThat(fixture.server.closed).isTrue();
				if (fixture.server.transport != null) {
					assertThat(fixture.server.transport.isTerminated()).isTrue();
				}
				// Include logs emitted during context shutdown in the leak assertions.
				fixture.assertNoSecrets(lastResult.get());
				System.out.println("PROBE_OK");
			}
			catch (Throwable failure) {
				System.err.println("PROBE_FAILED:" + failure.getClass().getSimpleName());
				System.exit(1);
			}
			finally {
				fixture.stopCapturingLogs();
			}
		}

		private static void assertClientsAreLazy(ConfigurableApplicationContext application) {
			for (String name : List.of("upbitOrderClient", "binanceAccountTradesClient", "localUpbitClient")) {
				assertThat(application.getBeanFactory().containsSingleton(name)).isFalse();
			}
		}

		private static void assertSafeFailure(Runnable action) {
			assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class)
					.hasMessage("Upbit request failed: UNSAFE_TRANSPORT_CONFIGURATION").hasNoCause()
					.satisfies(failure -> {
						assertThat(failure.getSuppressed()).isEmpty();
						StringWriter trace = new StringWriter();
						failure.printStackTrace(new PrintWriter(trace));
						assertThat(trace.toString()).doesNotContain(ACCESS, SECRET, MARKER, "Bearer ", "eyJ");
					});
		}
	}

	private void assertSafeError(CallToolResult result, String category) {
		assertThat(result.isError()).isTrue();
		assertThat(result.content()).isNotEmpty().allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(text.text()).contains("Upbit request failed: " + category)));
		assertNoSecrets(result);
	}

	private void assertNoSecrets(CallToolResult result) {
		List<String> observations = new ArrayList<>(List.of(
				result.content().toString(), String.valueOf(result.structuredContent())));
		for (ILoggingEvent event : logs.list) {
			observations.add(event.getFormattedMessage());
			if (event.getThrowableProxy() != null) {
				observations.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
			}
		}
		for (String observation : observations) {
			assertThat(observation).doesNotContain(ACCESS, SECRET, MARKER, "Bearer ", "eyJ",
					"MalformedInputException", "UnmappableCharacterException", "CharacterCodingException", "Input length",
					"StreamConstraintsException", "JsonParseException", "[Source:", "line: ", "column: ");
			for (String token : server.tokens) {
				assertThat(observation).doesNotContain(token, token.substring(7));
			}
		}
	}

	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		((Map<?, ?>) value).forEach((key, entryValue) -> {
			assertThat(key).isInstanceOf(String.class);
			result.put((String) key, entryValue);
		});
		return result;
	}

	static final class TransportCleanupListener extends AbstractTestExecutionListener {
		private static final String SERVER_ATTRIBUTE = TransportCleanupListener.class.getName() + ".server";

		@Override
		public int getOrder() {
			// After-class callbacks run in reverse order: inspect the retained fixture
			// only after @DirtiesContext has closed and evicted its owning context.
			return new DirtiesContextTestExecutionListener().getOrder() - 1;
		}

		@Override
		public void beforeTestMethod(TestContext testContext) {
			testContext.setAttribute(SERVER_ATTRIBUTE,
					testContext.getApplicationContext().getBean(LocalUpbit.class));
		}

		@Override
		public void afterTestClass(TestContext testContext) {
			// Do not look up the context here: that would create a fresh one after eviction.
			LocalUpbit server = (LocalUpbit) testContext.removeAttribute(SERVER_ATTRIBUTE);
			assertThat(server).isNotNull();
			assertThat(server.transport).isNotNull();
			assertThat(server.transport.isTerminated()).isTrue();
		}
	}

	// Restrict the runner's scan to production configuration; test fixtures must be explicit.
	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@ComponentScan(basePackageClasses = TradeJournalProperties.class)
	@Import({TradeLedgerTools.class, UpbitOrderTools.class, BinanceAccountTradesTools.class})
	static class ProbeConfiguration {
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class FailureConfiguration {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeLocalConfiguration extends LocalConfiguration {
		// Only the isolated startup probe needs lazy creation; keep the existing
		// SpringBootTest fixture's eager transport and after-context assertions.
		@Override
		@Bean
		@Primary
		@Lazy
		UpbitOrderClient localUpbitClient(LocalUpbit server) throws ReflectiveOperationException {
			return super.localUpbitClient(server);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LocalConfiguration {
		@Bean(destroyMethod = "close")
		LocalUpbit localUpbit() throws IOException {
			return new LocalUpbit();
		}

		@Bean
		@Primary
		UpbitOrderClient localUpbitClient(LocalUpbit server) throws ReflectiveOperationException {
			// Use the existing package-private, loopback-only test seam without adding
			// a configurable endpoint or credential argument to the production API.
			var constructor = UpbitOrderClient.class.getDeclaredConstructor(URI.class, Supplier.class);
			constructor.setAccessible(true);
			server.clientCreations.incrementAndGet();
			UpbitOrderClient client = constructor.newInstance(server.uri(), (Supplier<Map<String, String>>) () -> {
				server.credentialReads.incrementAndGet();
				return server.keys.get();
			});
			// Retain the owned transport for the existing after-context termination assertion.
			var transport = UpbitOrderClient.class.getDeclaredField("http");
			transport.setAccessible(true);
			server.transport = (HttpClient) transport.get(client);
			return client;
		}
	}

	static final class LocalUpbit implements AutoCloseable {
		final AtomicReference<Map<String, String>> keys = new AtomicReference<>(KEYS);
		final AtomicInteger status = new AtomicInteger(200);
		final AtomicReference<String> body = new AtomicReference<>(ORDER);
		final AtomicReference<byte[]> ignoredBytes = new AtomicReference<>();
		final AtomicInteger requests = new AtomicInteger();
		final AtomicInteger credentialReads = new AtomicInteger();
		final AtomicInteger clientCreations = new AtomicInteger();
		final List<String> tokens = new CopyOnWriteArrayList<>();
		final List<String> paths = new CopyOnWriteArrayList<>();
		final List<String> headers = new CopyOnWriteArrayList<>();
		final CountDownLatch releaseBody = new CountDownLatch(1);
		private final HttpServer http;
		private HttpClient transport;
		private boolean closed;

		LocalUpbit() throws IOException {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			http.createContext("/", exchange -> {
				requests.incrementAndGet();
				paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
				headers.add(new java.util.LinkedHashMap<>(exchange.getRequestHeaders()).toString());
				String token = exchange.getRequestHeaders().getFirst("Authorization");
				tokens.add(token);
				try (exchange) {
					if (status.get() == -1) {
						return;
					}
					if (status.get() == -2) {
						exchange.sendResponseHeaders(200, 100);
						try {
							releaseBody.await(12, TimeUnit.SECONDS);
						}
						catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
						}
						return;
					}
					String payload = body.get();
					if (!payload.contains("\"uuid\"") && !payload.stripLeading().startsWith("[")) {
						// Preserve valid permission JSON while putting secrets in an ignored field;
						// malformed bodies carry the same markers as raw text.
						String secrets = ACCESS + SECRET + token;
						payload = payload.startsWith("{")
								? payload.substring(0, payload.length() - 1) + ",\"private\":\"" + secrets + "\"}"
								: payload + secrets;
					}
					byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
					byte[] ignored = ignoredBytes.get();
					if (ignored != null) {
						bytes = withIgnoredBytes(payload, ignored, MARKER + ACCESS + SECRET + token);
					}
					exchange.sendResponseHeaders(status.get(), bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			});
			http.start();
		}

		URI uri() {
			return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1/order");
		}

		@Override
		public void close() {
			try {
				if (transport != null) {
					transport.close();
				}
			}
			finally {
				http.stop(0);
				closed = true;
			}
		}
	}
}
