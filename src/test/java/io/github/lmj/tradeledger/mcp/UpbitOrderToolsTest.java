package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
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
		server.requests.set(0);
		server.tokens.clear();
		server.paths.clear();
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
	void closedOrdersRejectsInvalidRequestsWithoutHttpEvenWhenKeysAreMissing(Map<String, Object> arguments) {
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
		}
		doThrow(new IllegalStateException(MARKER + ACCESS + SECRET)).when(provider).getObject();
		assertThatThrownBy(() -> tools.getUpbitClosedOrders(null, null, null, null, null, null, null))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
		assertThatThrownBy(() -> tools.getUpbitOrder(ID))
				.hasMessage("Upbit request failed: INTERNAL_ERROR").hasNoCause();
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

	private CallToolResult call(String uuid) {
		return call("get_upbit_order", Map.of("uuid", uuid));
	}

	private CallToolResult call(String name, Map<String, Object> arguments) {
		Object specs = context.getBean("toolSpecs");
		assertThat(specs).isInstanceOf(List.class);
		SyncToolSpecification tool = ((List<?>) specs).stream()
				.filter(SyncToolSpecification.class::isInstance).map(SyncToolSpecification.class::cast)
				.filter(specification -> specification.tool().name().equals(name))
				.findFirst().orElseThrow();
		return tool.callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
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
			assertThat(observation).doesNotContain(ACCESS, SECRET, MARKER, "Bearer ");
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
			UpbitOrderClient client = constructor.newInstance(server.uri(),
					(Supplier<Map<String, String>>) server.keys::get);
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
		final AtomicInteger requests = new AtomicInteger();
		final List<String> tokens = new CopyOnWriteArrayList<>();
		final List<String> paths = new CopyOnWriteArrayList<>();
		final CountDownLatch releaseBody = new CountDownLatch(1);
		private final HttpServer http;
		private HttpClient transport;

		LocalUpbit() throws IOException {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			http.createContext("/", exchange -> {
				requests.incrementAndGet();
				paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
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
			}
		}
	}
}
