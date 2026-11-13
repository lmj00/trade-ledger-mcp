package io.github.lmj.tradeledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.stream.Stream;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.lmj.tradeledger.TradeLedgerMcpApplication;
import io.github.lmj.tradeledger.adapter.toss.TossApiException;
import io.github.lmj.tradeledger.adapter.toss.TossClosedOrdersRequest;
import io.github.lmj.tradeledger.adapter.toss.TossErrorCode;
import io.github.lmj.tradeledger.adapter.toss.TossOrderClient;
import io.github.lmj.tradeledger.adapter.toss.TossOrderRequest;
import io.github.lmj.tradeledger.application.model.TossClosedOrdersSnapshot;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;
import io.github.lmj.tradeledger.config.TradeJournalProperties;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = {TradeLedgerMcpApplication.class, TossOrderToolsTest.LocalConfiguration.class},
		properties = "trade-ledger.journal.root=src/test/resources/journals")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = TossOrderToolsTest.TransportCleanupListener.class,
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class TossOrderToolsTest {
	private static final String NAME = "get_toss_order";
	private static final String CLOSED_NAME = "get_toss_closed_orders";
	private static final String ID = "PrivateMcpOrder%2F+";
	private static final String TOKEN = "fake-toss-mcp-token";
	private static final String ACCOUNT = "9007199254740993";
	private static final String PRIVATE = "private-toss-body-parser-marker";
	private static final String DISABLE = "jdk.httpclient.disableRetryConnect";
	private static final String RETRIES = "jdk.httpclient.redirects.retrylimit";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = Map.of("TOSS_ACCESS_TOKEN", TOKEN, "TOSS_ACCOUNT_SEQ", ACCOUNT);

	@Autowired ApplicationContext context;
	@Autowired LocalToss server;
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	@BeforeEach
	void resetAndCaptureLogs() {
		server.keys.set(KEYS);
		server.status.set(200);
		server.body.set(TossOrderResponseTest.envelope(0, ID));
		server.rawBody.set(null);
		server.requests.set(0);
		server.environmentReads.set(0);
		server.paths.clear();
		server.headers.clear();
		server.release.set(new CountDownLatch(1));
		logs.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
		for (String name : List.of("io.github.lmj.tradeledger", "org.springframework.ai.mcp", "io.modelcontextprotocol")) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			levels.put(logger, logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}
	}

	@AfterEach
	void stopCapture() {
		server.release.get().countDown();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
		levels.forEach(Logger::setLevel);
		logs.stop();
	}

	static Stream<String> invalidIds() {
		return Stream.of(null, "", " ", "\t", "x\n", "x\u0000", "x\u007f", "x\u0085", "x".repeat(257),
				"\ud800", "\udc00", "x\ud800y", "\ud800\ud800", "😀\udc00", PRIVATE + TOKEN + ACCOUNT + "\n" + ID);
	}

	@ParameterizedTest
	@MethodSource("invalidIds")
	@SuppressWarnings("unchecked")
	void rejectsInvalidIdsBeforeProviderAndEnvironmentEvenWithoutCredentials(String id) {
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossOrder(id))
				.hasMessage("Toss request failed: BAD_REQUEST").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		verifyNoInteractions(provider);
		server.keys.set(Map.of());
		int creations = server.clientCreations.get();
		assertSafeError(call(context, arguments(id)), "BAD_REQUEST");
		assertThat(server.clientCreations).hasValue(creations);
		assertThat(server.environmentReads).hasValue(0);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void closedToolValidatesThenObtainsProviderAndQueriesExactlyOnePage() {
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		TossOrderClient client = mock(TossOrderClient.class);
		doReturn(client).when(provider).getObject();
		var request = new TossClosedOrdersRequest("aApL.BrK-1", "2026-03-28", null, " opaque%2F=+/&?#한😀 ", 100);
		var page = new TossClosedOrdersSnapshot(List.of(TossOrderResponseTest.snapshot(3, ID)), " next ", true);
		when(client.getClosedOrders(request)).thenReturn(page);
		assertThat(new TossOrderTools(provider).getTossClosedOrders(request.symbol(), request.from(), request.to(),
				request.cursor(), request.limit())).isEqualTo(TossClosedOrdersResponse.from(page));
		verify(provider).getObject();
		verify(client).getClosedOrders(request);
		verifyNoMoreInteractions(provider, client);
	}

	static Stream<Map<String, Object>> invalidClosedArguments() {
		List<Map<String, Object>> cases = new ArrayList<>();
		for (String field : List.of("symbol", "from", "to", "cursor")) {
			for (Object wrong : List.of(123, 1.25, true, List.of(PRIVATE), Map.of(PRIVATE, TOKEN))) {
				cases.add(Map.of(field, wrong));
			}
		}
		for (Object wrong : List.of(0, 101, -1, 1.25, 1.0, new BigDecimal("1.0"), "20", true,
				List.of(PRIVATE), Map.of(PRIVATE, TOKEN), Long.MAX_VALUE, 4294967316L,
				new BigInteger("18446744073709551636"))) {
			cases.add(Map.of("limit", wrong));
		}
		for (String wrong : List.of("", " ", "A/B", "a_b", "한", "x".repeat(257), PRIVATE + TOKEN + ACCOUNT + "!")) {
			cases.add(Map.of("symbol", wrong));
		}
		for (String wrong : List.of("", " ", "\u2003", "x\n", "x\u0000", "x\u007f", "x\u0085", "x".repeat(257),
				"\ud800", "\udc00", "x\ud800y", "\ud800\ud800", "😀\udc00", PRIVATE + TOKEN + ACCOUNT + "\n")) {
			cases.add(Map.of("cursor", wrong));
		}
		for (String field : List.of("from", "to")) {
			for (String wrong : List.of("", "2026-2-01", "2026-02-29", "2026-04-31", "2026-03-28T00:00:00Z",
					" 2026-03-28", "2026-03-28 ", "２０２６-03-28", PRIVATE + TOKEN + ACCOUNT)) {
				cases.add(Map.of(field, wrong));
			}
		}
		cases.add(Map.of("from", "2026-03-29", "to", "2026-03-28"));
		return cases.stream();
	}

	@ParameterizedTest
	@MethodSource("invalidClosedArguments")
	@SuppressWarnings("unchecked")
	void closedInvalidInputsNeverObtainProviderReadEnvironmentOrTransmit(Map<String, Object> arguments) {
		// Typed Java calls also validate before provider access; wrong JSON types
		// are tested through the registered handler before adapter coercion.
		if (arguments.entrySet().stream().allMatch(entry -> entry.getKey().equals("limit")
				? entry.getValue() instanceof Integer : entry.getValue() instanceof String)) {
			ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
			assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(
					(String) arguments.get("symbol"), (String) arguments.get("from"), (String) arguments.get("to"),
					(String) arguments.get("cursor"), (Integer) arguments.get("limit")))
					.hasMessage("Toss request failed: BAD_REQUEST").hasNoCause()
					.satisfies(TossOrderToolsTest::assertSafeThrowable);
			verifyNoInteractions(provider);
		}
		server.keys.set(Map.of());
		int creations = server.clientCreations.get();
		assertSafeError(callNamed(context, CLOSED_NAME, arguments), "BAD_REQUEST");
		assertThat(server.clientCreations).hasValue(creations);
		assertThat(server.environmentReads).hasValue(0);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	void registeredInvalidClosedInputsNeverResolveEvenAnUncreatedLazyClient() {
		AtomicInteger resolutions = new AtomicInteger();
		runner().withBean("unavailableTossClient", TossOrderClient.class, () -> {
			resolutions.incrementAndGet();
			throw new IllegalStateException(PRIVATE + TOKEN + ACCOUNT);
		}, definition -> { definition.setPrimary(true); definition.setLazyInit(true); }).run(application -> {
			assertThat(application).hasNotFailed();
			invalidClosedArguments().forEach(arguments ->
					assertSafeError(callNamed(application, CLOSED_NAME, arguments), "BAD_REQUEST"));
			assertThat(resolutions).hasValue(0);
		});
	}

	@Test
	void closedDefaultNullAndBoundaryRequestsSendOnlySpecifiedFiltersOnce() {
		server.body.set(closedEnvelope(null, false));
		List<Map<String, Object>> cases = new ArrayList<>();
		cases.add(null);
		cases.add(Map.of());
		Map<String, Object> nulls = new LinkedHashMap<>();
		for (String field : List.of("symbol", "from", "to", "cursor", "limit")) { nulls.put(field, null); }
		cases.add(nulls);
		cases.add(Map.of("limit", 1));
		cases.add(Map.of("limit", 100));
		cases.add(Map.of("from", "2024-02-29"));
		cases.add(Map.of("to", "2026-03-28"));
		cases.add(Map.of("from", "2026-03-28", "to", "2026-03-28"));
		for (Map<String, Object> arguments : cases) {
			int before = server.requests.get();
			var result = callNamed(context, CLOSED_NAME, arguments);
			assertThat(result.isError()).isFalse();
			assertThat(server.requests).hasValue(before + 1);
			Map<String, String> expected = new LinkedHashMap<>(Map.of("status", "CLOSED", "limit", "20"));
			if (arguments != null) {
				arguments.forEach((key, value) -> { if (value != null) { expected.put(key, value.toString()); } });
			}
			assertClosedQuery(expected);
		}
		// Also exercise the MCP request with an omitted arguments property.
		var spec = ((List<?>) context.getBean("toolSpecs")).stream().map(SyncToolSpecification.class::cast)
				.filter(candidate -> candidate.tool().name().equals(CLOSED_NAME)).findFirst().orElseThrow();
		int before = server.requests.get();
		assertThat(spec.callHandler().apply(null, CallToolRequest.builder(CLOSED_NAME).build()).isError()).isFalse();
		assertThat(server.requests).hasValue(before + 1);
		assertClosedQuery(Map.of("status", "CLOSED", "limit", "20"));
	}

	@Test
	void closedFiltersPreserveOpaqueCursorAndSymbolWithoutChangingRequestTargetOrHeaders() {
		server.body.set(closedEnvelope(null, false));
		String cursor = " opaque%2F=+/&?#한😀 ";
		Map<String, Object> arguments = Map.of("symbol", "aApL.BrK-1", "from", "2026-03-01", "to", "2026-03-28",
				"cursor", cursor, "limit", 100);
		assertThat(callNamed(context, CLOSED_NAME, arguments).isError()).isFalse();
		assertThat(server.requests).hasValue(1);
		assertThat(server.paths).containsExactly("GET /api/v1/orders?status=CLOSED&limit=100&symbol=aApL%2EBrK-1"
				+ "&from=2026-03-01&to=2026-03-28&cursor=%20opaque%252F%3D%2B%2F%26%3F%23%ED%95%9C%F0%9F%98%80%20");
		assertClosedQuery(Map.of("status", "CLOSED", "limit", "100", "symbol", "aApL.BrK-1", "from", "2026-03-01",
				"to", "2026-03-28", "cursor", cursor));
		for (String boundary : List.of("x".repeat(256), "😀".repeat(128))) {
			int before = server.requests.get();
			assertThat(callNamed(context, CLOSED_NAME, Map.of("symbol", "a".repeat(256), "cursor", boundary)).isError()).isFalse();
			assertThat(server.requests).hasValue(before + 1);
			assertClosedQuery(Map.of("status", "CLOSED", "limit", "20", "symbol", "a".repeat(256), "cursor", boundary));
		}
	}

	private void assertClosedQuery(Map<String, String> expected) {
		URI target = URI.create(server.paths.getLast().substring("GET ".length()));
		assertThat(target.getRawPath()).isEqualTo("/api/v1/orders");
		assertThat(target.getRawFragment()).isNull();
		Map<String, String> actual = new LinkedHashMap<>();
		for (String pair : target.getRawQuery().split("&")) {
			String[] parts = pair.split("=", 2);
			assertThat(actual.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8))).isNull();
		}
		assertThat(actual).isEqualTo(expected);
		assertThat(server.headers.getLast()).isEqualTo("Bearer " + TOKEN + "|" + ACCOUNT + "|application/json");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 4, 100})
	void registeredClosedPagesKeepExactSummariesNullsAndServerMetadata(int count) {
		String[] orders = IntStream.range(0, count).mapToObj(i -> TossOrderResponseTest.envelope(i % 4, "order-" + i))
				.toArray(String[]::new);
		for (boolean hasNext : List.of(false, true)) {
			String cursor = hasNext ? " next%2F=+/&?#한😀 " : null;
			server.body.set(closedEnvelope(cursor, hasNext, orders));
			int before = server.requests.get();
			var result = callNamed(context, CLOSED_NAME, Map.of("limit", 100));
			assertThat(result.isError()).isFalse();
			var actual = TossOrderResponseTest.JSON.valueToTree(result.structuredContent());
			var expected = TossOrderResponseTest.JSON.readTree("{\"serviceId\":\"toss\",\"orders\":["
					+ String.join(",", IntStream.range(0, count).mapToObj(i -> TossOrderResponseTest.expected(i % 4, "order-" + i)).toList())
					+ "],\"nextCursor\":" + TossOrderResponseTest.JSON.writeValueAsString(cursor) + ",\"hasNext\":" + hasNext + "}");
			assertThat(actual).isEqualTo(expected);
			assertThat(result.content()).hasSize(1).allSatisfy(content ->
					assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
							assertThat(TossOrderResponseTest.JSON.readTree(text.text())).isEqualTo(expected)));
			assertThat(server.requests).hasValue(before + 1);
		}
	}

	private static String closedEnvelope(String cursor, boolean hasNext, String... wrappedOrders) {
		List<String> orders = Stream.of(wrappedOrders)
				.map(json -> json.substring("{\"result\":".length(), json.length() - 1)).toList();
		return "{\"result\":{\"orders\":[" + String.join(",", orders) + "],\"nextCursor\":"
				+ TossOrderResponseTest.JSON.writeValueAsString(cursor) + ",\"hasNext\":" + hasNext + "}}";
	}

	@Test
	void registeredMissingNullAndWrongJsonTypesNeverCreateClientOrReadEnvironment() {
		int creations = server.clientCreations.get();
		assertSafeError(call(context, Map.of()), "BAD_REQUEST");
		for (Object wrong : List.of(123, true, List.of(PRIVATE), Map.of(PRIVATE, TOKEN), 1.25)) {
			assertSafeError(call(context, Map.of("orderId", wrong)), "BAD_REQUEST");
		}
		assertSafeError(call(context, arguments(null)), "BAD_REQUEST");
		assertThat(server.clientCreations).hasValue(creations);
		assertThat(server.environmentReads).hasValue(0);
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3})
	void registeredSummariesHaveExactFieldsNullsAndMatchingTextAndStructuredContent(int kind) {
		server.body.set(TossOrderResponseTest.envelope(kind, ID));
		CallToolResult result = call(context, arguments(ID));
		assertThat(result.isError()).isFalse();
		var expected = TossOrderResponseTest.JSON.readTree(TossOrderResponseTest.expected(kind, ID));
		JsonNode actual = TossOrderResponseTest.JSON.valueToTree(result.structuredContent());
		assertThat(actual).isEqualTo(expected);
		assertThat(result.content()).hasSize(1).allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(TossOrderResponseTest.JSON.readTree(text.text())).isEqualTo(expected)));
		assertThat(server.requests).hasValue(1);
		assertThat(server.environmentReads).hasValue(1);
		assertThat(server.paths).containsExactly("GET /api/v1/orders/PrivateMcpOrder%252F%2B");
		assertThat(server.headers).containsExactly("Bearer " + TOKEN + "|" + ACCOUNT + "|application/json");
	}

	@Test
	void registeredOpaqueIdsArePreservedAndEncodedAsExactlyOnePathSegment() {
		Map<String, String> cases = Map.ofEntries(
				Map.entry("%2F", "%252F"), Map.entry("/", "%2F"), Map.entry("+", "%2B"),
				Map.entry(" x ", "%20x%20"), Map.entry("?", "%3F"), Map.entry("#", "%23"),
				Map.entry("한😀", "%ED%95%9C%F0%9F%98%80"), Map.entry(".", "%2E"),
				Map.entry("..", "%2E%2E"), Map.entry("../x", "%2E%2E%2Fx"),
				Map.entry("aA0-_~", "aA0-_~"), Map.entry("x".repeat(256), "x".repeat(256)),
				Map.entry("😀".repeat(128), "%F0%9F%98%80".repeat(128)));
		for (var entry : cases.entrySet()) {
			server.body.set(TossOrderResponseTest.envelope(0, entry.getKey()));
			int before = server.requests.get();
			var result = call(context, arguments(entry.getKey()));
			assertThat(result.isError()).isFalse();
			assertThat(TossOrderResponseTest.JSON.valueToTree(result.structuredContent()).get("orderId").asString())
					.isEqualTo(entry.getKey());
			assertThat(server.requests).hasValue(before + 1);
			assertThat(server.paths.getLast()).isEqualTo("GET /api/v1/orders/" + entry.getValue());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void validToolCallObtainsProviderAndQueriesExistingClientExactlyOnce() {
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		TossOrderClient client = mock(TossOrderClient.class);
		doReturn(client).when(provider).getObject();
		when(client.getOrder(new TossOrderRequest(ID))).thenReturn(TossOrderResponseTest.snapshot(3, ID));
		assertThat(new TossOrderTools(provider).getTossOrder(ID))
				.isEqualTo(TossOrderResponse.from(TossOrderResponseTest.snapshot(3, ID)));
		verify(provider).getObject();
		verify(client).getOrder(new TossOrderRequest(ID));
		verifyNoMoreInteractions(provider, client);
	}

	@ParameterizedTest
	@EnumSource(TossErrorCode.class)
	@SuppressWarnings("unchecked")
	void everySafeCategorySurvivesWrappedProviderAndClientFailuresWithoutTheirGraphs(TossErrorCode code) throws Exception {
		var failure = new BeanCreationException(PRIVATE + TOKEN + ACCOUNT + ID, safeException(code));
		failure.addSuppressed(new IllegalStateException(PRIVATE));
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenThrow(failure);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossOrder(ID))
				.hasMessage("Toss request failed: " + code).hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(null, null, null, null, null))
				.hasMessage("Toss request failed: " + code).hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		TossOrderClient client = mock(TossOrderClient.class);
		doReturn(client).when(provider).getObject();
		when(client.getOrder(any())).thenThrow(safeException(code));
		when(client.getClosedOrders(any())).thenThrow(safeException(code));
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossOrder(ID))
				.hasMessage("Toss request failed: " + code).hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(null, null, null, null, null))
				.hasMessage("Toss request failed: " + code).hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
	}

	@Test
	@SuppressWarnings("unchecked")
	void lastOrderProjectionFailureDiscardsEarlierProjectionAndPrivateExceptionGraph() {
		RuntimeException failure = new IllegalStateException(PRIVATE + TOKEN + ACCOUNT + ID,
				new IllegalArgumentException(PRIVATE));
		failure.addSuppressed(new IllegalStateException(TOKEN));
		TossOrderSnapshot damaged = mock(TossOrderSnapshot.class);
		when(damaged.orderId()).thenReturn("damaged-order");
		when(damaged.execution()).thenThrow(failure);
		var page = new TossClosedOrdersSnapshot(List.of(TossOrderResponseTest.snapshot(0, "first"), damaged), null, false);
		TossOrderClient client = mock(TossOrderClient.class);
		when(client.getClosedOrders(any())).thenReturn(page);
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		doReturn(client).when(provider).getObject();
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(null, null, null, null, null))
				.hasMessage("Toss request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		runner().withBean("projectionFailureTossClient", TossOrderClient.class, () -> client, definition -> {
			definition.setPrimary(true);
			definition.setLazyInit(true);
			definition.setDestroyMethodName("close");
		}).run(application -> {
			assertThat(application).hasNotFailed();
			assertSafeError(callNamed(application, CLOSED_NAME, Map.of()), "INTERNAL_ERROR");
		});
		verify(provider).getObject();
		verify(client, org.mockito.Mockito.times(2)).getClosedOrders(new TossClosedOrdersRequest());
		verify(client).close();
		verifyNoMoreInteractions(provider, client);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	void registeredLazyFailuresDiscardPrivateConfigurationAndAllSafeCategories() {
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		runner().withBean("failingTossClient", TossOrderClient.class, () -> { throw failure.get(); },
				definition -> { definition.setPrimary(true); definition.setLazyInit(true); })
				.run(application -> {
					assertThat(application).hasNotFailed();
					for (TossErrorCode code : TossErrorCode.values()) {
						failure.set(new BeanCreationException(PRIVATE + TOKEN + ACCOUNT + ID, safeException(code)));
						assertSafeError(call(application, arguments(ID)), code.name());
						assertSafeError(callNamed(application, CLOSED_NAME, Map.of()), code.name());
					}
					failure.set(new BeanCreationException(PRIVATE + TOKEN + ACCOUNT + ID,
							new IllegalStateException(PRIVATE)));
					assertSafeError(call(application, arguments(ID)), "INTERNAL_ERROR");
					assertSafeError(callNamed(application, CLOSED_NAME, Map.of()), "INTERNAL_ERROR");
				});
		assertThat(server.requests).hasValue(0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void unknownCyclicProviderAndConversionFailuresBecomeInternalError() {
		RuntimeException first = new IllegalStateException(PRIVATE + TOKEN + ACCOUNT + ID);
		RuntimeException second = new IllegalArgumentException(PRIVATE, first);
		first.initCause(second);
		first.addSuppressed(new IllegalStateException(PRIVATE));
		ObjectProvider<TossOrderClient> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenThrow(first);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossOrder(ID))
				.hasMessage("Toss request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(null, null, null, null, null))
				.hasMessage("Toss request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		TossOrderClient client = mock(TossOrderClient.class);
		doReturn(client).when(provider).getObject();
		when(client.getOrder(any())).thenReturn(null);
		when(client.getClosedOrders(any())).thenReturn(null);
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossOrder(ID))
				.hasMessage("Toss request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		verify(client).getOrder(new TossOrderRequest(ID));
		assertThatThrownBy(() -> new TossOrderTools(provider).getTossClosedOrders(null, null, null, null, null))
				.hasMessage("Toss request failed: INTERNAL_ERROR").hasNoCause()
				.satisfies(TossOrderToolsTest::assertSafeThrowable);
		verify(client).getClosedOrders(new TossClosedOrdersRequest());
		runner().withBean("faultyTossClient", TossOrderClient.class, () -> client, definition -> {
			definition.setPrimary(true);
			definition.setLazyInit(true);
			definition.setDestroyMethodName("close");
		}).run(application -> {
			assertThat(application).hasNotFailed();
			assertSafeError(call(application, arguments(ID)), "INTERNAL_ERROR");
			assertSafeError(callNamed(application, CLOSED_NAME, Map.of()), "INTERNAL_ERROR");
		});
		verify(client, org.mockito.Mockito.times(2)).getOrder(new TossOrderRequest(ID));
		verify(client, org.mockito.Mockito.times(2)).getClosedOrders(new TossClosedOrdersRequest());
		verify(client).close();
		verifyNoMoreInteractions(client);
		assertThat(server.requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 429, 302, 500, 418})
	void registeredHttpErrorsUseOnlyStatusAndNeverResendOrExposePrivateBody(int status) {
		server.status.set(status);
		server.body.set(PRIVATE + TOKEN + ACCOUNT + ID);
		String category = switch (status) {
			case 400 -> "BAD_REQUEST";
			case 401 -> "AUTHENTICATION_FAILED";
			case 403 -> "PERMISSION_DENIED";
			case 404 -> "ORDER_NOT_FOUND";
			case 429 -> "RATE_LIMITED";
			case 302 -> "REDIRECT_REJECTED";
			case 500 -> "SERVER_ERROR";
			default -> "UNEXPECTED_STATUS";
		};
		assertSafeError(call(context, arguments(ID)), category);
		assertThat(server.requests).hasValue(1);
		assertSafeError(callNamed(context, CLOSED_NAME, Map.of()), category);
		assertThat(server.requests).hasValue(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"overLimit", "duplicate", "metadata", "missingMetadata", "lastDamaged", "utf8", "oversized", "timeout"})
	void registeredClosedFailuresRejectWholePageAndNeverTransmitAgain(String scenario) {
		String first = TossOrderResponseTest.envelope(0, "first");
		String category = "INVALID_RESPONSE";
		Map<String, Object> arguments = Map.of();
		switch (scenario) {
			case "overLimit" -> {
				server.body.set(closedEnvelope(null, false, first, TossOrderResponseTest.envelope(1, "last")));
				arguments = Map.of("limit", 1);
			}
			case "duplicate" -> server.body.set(closedEnvelope(null, false, first, first));
			case "metadata" -> server.body.set(closedEnvelope(PRIVATE, false, first));
			case "missingMetadata" -> server.body.set(closedEnvelope(null, false, first).replace("\"hasNext\":false", "\"ignored\":false"));
			case "lastDamaged" -> server.body.set(closedEnvelope(null, false, first,
					TossOrderResponseTest.envelope(3, "last").replace("\"filledQuantity\":\"2.00\"", "\"filledQuantity\":\"" + PRIVATE + "\"")));
			case "utf8" -> {
				byte[] body = closedEnvelope(null, false, first).getBytes(StandardCharsets.UTF_8);
				// A malformed byte within a JSON string must not be replaced silently.
				int index = new String(body, StandardCharsets.UTF_8).indexOf("first");
				body[index] = (byte) 0xc3;
				server.rawBody.set(body);
			}
			case "oversized" -> {
				server.body.set(" ".repeat(1_048_577) + PRIVATE);
				category = "RESPONSE_TOO_LARGE";
			}
			case "timeout" -> {
				server.status.set(-2);
				category = "TIMEOUT";
			}
			default -> throw new AssertionError(scenario);
		}
		try {
			assertSafeError(callNamed(context, CLOSED_NAME, arguments), category);
			assertThat(server.requests).hasValue(1);
		}
		finally { server.release.get().countDown(); }
	}

	@ParameterizedTest
	@ValueSource(strings = {"damaged", "mismatch", "oversized", "timeout"})
	void registeredInvalidAndBoundedResponsesHaveNoPartialContentOrSecondTransmission(String scenario) {
		String category = "INVALID_RESPONSE";
		switch (scenario) {
			case "damaged" -> server.body.set("{\"" + PRIVATE + TOKEN + ACCOUNT + ID + "\":}");
			case "mismatch" -> server.body.set(TossOrderResponseTest.envelope(0, PRIVATE));
			case "oversized" -> {
				server.body.set(" ".repeat(1_048_577) + PRIVATE);
				category = "RESPONSE_TOO_LARGE";
			}
			case "timeout" -> {
				server.status.set(-2);
				category = "TIMEOUT";
			}
			default -> throw new AssertionError(scenario);
		}
		try {
			assertSafeError(call(context, arguments(ID)), category);
			assertThat(server.requests).hasValue(1);
		}
		finally { server.release.get().countDown(); }
	}

	@Test
	void validOptionsButMissingCredentialsNeverTransmit() {
		server.keys.set(Map.of());
		assertSafeError(call(context, arguments(ID)), "MISSING_CREDENTIALS");
		assertThat(server.environmentReads).hasValue(1);
		assertThat(server.requests).hasValue(0);
		assertSafeError(callNamed(context, CLOSED_NAME, Map.of()), "MISSING_CREDENTIALS");
		assertThat(server.environmentReads).hasValue(2);
		assertThat(server.requests).hasValue(0);
	}

	@Test
	void independentJvmRegistrationCalculationsLazyFailuresAndShutdown() throws Exception {
		List<String> valid = List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=1");
		assertThat(runProbe(List.of(), "unsafe")).contains("PROBE_OK");
		assertThat(runProbe(List.of("-D" + DISABLE + "=false", "-D" + RETRIES + "=1"), "unsafe"))
				.contains("PROBE_OK");
		assertThat(runProbe(valid, "missing")).contains("PROBE_OK");
		List<String> logging = new ArrayList<>(valid);
		logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
		assertThat(runProbe(logging, "unsafe")).contains("PROBE_OK");
		assertThat(runProbe(logging, "clearedLogging")).contains("PROBE_OK");
	}

	private static String runProbe(List<String> options, String mode) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).isNotBlank();
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, StartupProbe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
				"TOSS_ACCESS_TOKEN", "TOSS_ACCOUNT_SEQ", "UPBIT_ACCESS_KEY", "UPBIT_SECRET_KEY",
				"BINANCE_API_KEY", "BINANCE_SECRET_KEY")) {
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
			assertThat(observed).doesNotContain(TOKEN, ACCOUNT, PRIVATE, ID);
			return observed;
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	public static final class StartupProbe {
		public static void main(String[] args) {
			try {
				for (String lookup : List.of(NAME, CLOSED_NAME)) {
					AtomicReference<LocalToss> retained = new AtomicReference<>();
					runner().withUserConfiguration(LocalConfiguration.class).withPropertyValues(
							"logging.console.enabled=false", "spring.main.banner-mode=off").run(application -> {
						assertThat(application).hasNotFailed();
						LocalToss local = application.getBean(LocalToss.class);
						retained.set(local);
						local.keys.set(Map.of());
						assertLazy(application);
						List<?> specs = (List<?>) application.getBean("toolSpecs");
						assertThat(specs.stream().map(SyncToolSpecification.class::cast).map(spec -> spec.tool().name()))
								.containsExactlyInAnyOrder("summarize_journal", "analyze_trades", "get_upbit_order",
										"get_upbit_closed_orders", "get_upbit_order_executions", "get_binance_account_trades",
										"get_binance_account_executions", NAME, CLOSED_NAME);
						CallToolResult summary = callNamed(application, "summarize_journal", Map.of("journalName", "sample-trades"));
						assertThat(summary.isError()).isNotEqualTo(Boolean.TRUE);
						assertThat(TossOrderResponseTest.JSON.valueToTree(summary.structuredContent()).get("totalNetPnl").asString())
								.isEqualTo("355.78645");
						Map<String, Object> trade = Map.ofEntries(
								Map.entry("tradeId", "probe-trade"), Map.entry("symbol", "BTC-USDT"),
								Map.entry("market", "CRYPTO"), Map.entry("direction", "LONG"),
								Map.entry("quantity", "1"), Map.entry("entryPrice", "100"), Map.entry("exitPrice", "110"),
								Map.entry("fees", "1"), Map.entry("currency", "USDT"),
								Map.entry("openedAt", "2026-07-01T00:00:00Z"),
								Map.entry("closedAt", "2026-07-01T01:00:00Z"), Map.entry("strategy", "probe"));
						CallToolResult analysis = callNamed(application, "analyze_trades", Map.of("trades", List.of(trade)));
						assertThat(analysis.isError()).isNotEqualTo(Boolean.TRUE);
						assertThat(TossOrderResponseTest.JSON.valueToTree(analysis.structuredContent()).get("totalNetPnl").asString())
								.isEqualTo("9");
						assertError(call(application, Map.of()), "BAD_REQUEST");
						assertLazy(application);
						assertThat(local.environmentReads).hasValue(0);
						assertThat(local.clientCreations).hasValue(0);
						if (args[0].equals("clearedLogging")) { System.clearProperty(HTTP_LOG); }
						assertError(callNamed(application, CLOSED_NAME, Map.of("limit", 1.25)), "BAD_REQUEST");
						assertLazy(application);
						assertError(callNamed(application, lookup, lookup.equals(NAME) ? arguments(ID) : Map.of()), args[0].equals("missing")
								? "MISSING_CREDENTIALS" : "UNSAFE_TRANSPORT_CONFIGURATION");
						assertThat(local.requests).hasValue(0);
						assertThat(local.clientCreations).hasValue(1);
						assertThat(local.environmentReads).hasValue(args[0].equals("missing") ? 1 : 0);
					});
					LocalToss local = retained.get();
					assertThat(local.closed).isTrue();
					if (local.transport != null) { assertThat(local.transport.isTerminated()).isTrue(); }
				}
				System.out.println("PROBE_OK");
			}
			catch (Throwable failure) {
				failure.printStackTrace();
				System.exit(1);
			}
		}

		private static void assertLazy(org.springframework.context.ConfigurableApplicationContext application) {
			for (String bean : List.of("upbitOrderClient", "binanceAccountTradesClient", "tossOrderClient", "localTossClient")) {
				assertThat(application.getBeanFactory().containsSingleton(bean)).isFalse();
			}
			assertThat(application.getBeanFactory().getBeanDefinition("tossOrderClient").isLazyInit()).isTrue();
			assertThat(application.getBeanFactory().getBeanDefinition("tossOrderClient").getDestroyMethodName()).isEqualTo("close");
		}
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(RunnerConfiguration.class)
				.withPropertyValues("trade-ledger.journal.root=src/test/resources/journals",
						"spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=true");
	}

	private static CallToolResult call(ApplicationContext application, Map<String, Object> arguments) {
		return callNamed(application, NAME, arguments);
	}

	private static CallToolResult callNamed(ApplicationContext application, String name, Map<String, Object> arguments) {
		List<?> specs = (List<?>) application.getBean("toolSpecs");
		var specification = specs.stream().map(SyncToolSpecification.class::cast)
				.filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
		return specification.callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
	}

	private static Map<String, Object> arguments(String id) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("orderId", id);
		return arguments;
	}

	private static TossApiException safeException(TossErrorCode code) throws Exception {
		var constructor = TossApiException.class.getDeclaredConstructor(TossErrorCode.class);
		constructor.setAccessible(true);
		return constructor.newInstance(code);
	}

	private static void assertSafeThrowable(Throwable failure) {
		StringWriter stack = new StringWriter();
		failure.printStackTrace(new PrintWriter(stack));
		assertThat(stack.toString()).doesNotContain(TOKEN, ACCOUNT, PRIVATE, ID,
				"JsonParseException", "StreamReadException", "Unexpected character", "column:");
		assertThat(failure.getSuppressed()).isEmpty();
	}

	private static void assertError(CallToolResult result, String category) {
		assertThat(result.isError()).isTrue();
		assertThat(result.structuredContent()).isNull();
		assertThat(result.content()).hasSize(1).allSatisfy(content ->
				assertThat(content).isInstanceOfSatisfying(TextContent.class, text ->
						assertThat(text.text()).isEqualTo("Toss request failed: " + category)));
	}

	private void assertSafeError(CallToolResult result, String category) {
		assertError(result, category);
		List<String> observations = new ArrayList<>(List.of(result.content().toString(), String.valueOf(result.structuredContent())));
		for (ILoggingEvent event : logs.list) {
			observations.add(event.getFormattedMessage());
			if (event.getThrowableProxy() != null) { observations.add(ThrowableProxyUtil.asString(event.getThrowableProxy())); }
		}
		for (String observed : observations) {
			assertThat(observed).doesNotContain(TOKEN, ACCOUNT, PRIVATE, ID,
					"JsonParseException", "StreamReadException", "Unexpected character", "column:");
		}
	}

	static final class TransportCleanupListener extends AbstractTestExecutionListener {
		private static final String SERVER = TransportCleanupListener.class.getName() + ".server";
		@Override public int getOrder() { return new DirtiesContextTestExecutionListener().getOrder() - 1; }
		@Override public void beforeTestMethod(TestContext testContext) {
			testContext.setAttribute(SERVER, testContext.getApplicationContext().getBean(LocalToss.class));
		}
		@Override public void afterTestClass(TestContext testContext) {
			LocalToss local = (LocalToss) testContext.removeAttribute(SERVER);
			assertThat(local).isNotNull();
			assertThat(local.transport).isNotNull();
			assertThat(local.transport.isTerminated()).isTrue();
			assertThat(local.closed).isTrue();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@ComponentScan(basePackageClasses = TradeJournalProperties.class)
	@Import({TradeLedgerTools.class, UpbitOrderTools.class, BinanceAccountTradesTools.class, TossOrderTools.class})
	static class RunnerConfiguration { }

	@TestConfiguration(proxyBeanMethods = false)
	static class LocalConfiguration {
		@Bean(destroyMethod = "close")
		LocalToss localToss() throws IOException { return new LocalToss(); }

		@Bean(destroyMethod = "close")
		@Primary
		@Lazy
		TossOrderClient localTossClient(LocalToss local) throws ReflectiveOperationException {
			local.clientCreations.incrementAndGet();
			var constructor = TossOrderClient.class.getDeclaredConstructor(URI.class, Supplier.class);
			constructor.setAccessible(true);
			TossOrderClient client = constructor.newInstance(local.uri(), (Supplier<Map<String, String>>) () -> {
				local.environmentReads.incrementAndGet();
				return local.keys.get();
			});
			var transport = TossOrderClient.class.getDeclaredField("http");
			transport.setAccessible(true);
			local.transport = (HttpClient) transport.get(client);
			return client;
		}
	}

	static final class LocalToss implements AutoCloseable {
		final AtomicReference<Map<String, String>> keys = new AtomicReference<>(KEYS);
		final AtomicInteger requests = new AtomicInteger();
		final AtomicInteger clientCreations = new AtomicInteger();
		final AtomicInteger environmentReads = new AtomicInteger();
		final AtomicInteger status = new AtomicInteger(200);
		final AtomicReference<String> body = new AtomicReference<>();
		final AtomicReference<byte[]> rawBody = new AtomicReference<>();
		final List<String> paths = new CopyOnWriteArrayList<>();
		final List<String> headers = new CopyOnWriteArrayList<>();
		final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
		final AtomicBoolean closed = new AtomicBoolean();
		private final HttpServer http;
		private HttpClient transport;

		LocalToss() throws IOException {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			http.createContext("/", exchange -> {
				requests.incrementAndGet();
				paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
				headers.add(exchange.getRequestHeaders().getFirst("Authorization") + "|"
						+ exchange.getRequestHeaders().getFirst("X-Tossinvest-Account") + "|"
						+ exchange.getRequestHeaders().getFirst("Accept"));
				try (exchange) {
					if (status.get() == -2) {
						exchange.sendResponseHeaders(200, 100);
						try { release.get().await(12, TimeUnit.SECONDS); }
						catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
						return;
					}
					if (status.get() == 302) { exchange.getResponseHeaders().set("Location", uri().toString()); }
					byte[] bytes = rawBody.get() == null ? body.get().getBytes(StandardCharsets.UTF_8) : rawBody.get();
					exchange.sendResponseHeaders(status.get(), bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			});
			http.start();
		}

		URI uri() { return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/api/v1/orders/"); }

		@Override public void close() {
			// Spring must close the client separately; do not close its transport here.
			release.get().countDown();
			http.stop(0);
			closed.set(true);
		}
	}
}
