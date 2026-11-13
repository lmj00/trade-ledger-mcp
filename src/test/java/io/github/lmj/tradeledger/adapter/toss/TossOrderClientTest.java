package io.github.lmj.tradeledger.adapter.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;

@ExtendWith(OutputCaptureExtension.class)
class TossOrderClientTest {
	private static final String TOKEN = "fake-toss-token-marker";
	private static final String ACCOUNT = "9007199254740993";
	private static final String ID = "PrivateOrder%2F+";
	private static final String CURSOR = " private-cursor=+/%2F한😀&injected=yes?# ";
	private static final String RAW_CURSOR = "%20private-cursor%3D%2B%2F%252F%ED%95%9C%F0%9F%98%80%26injected%3Dyes%3F%23%20";
	private static final String PRIVATE = "private-response-marker";
	private static final String PATH = "/api/v1/orders/";
	private static final String RAW_ID = "PrivateOrder%252F%2B";
	private static final String DISABLE = "jdk.httpclient.disableRetryConnect";
	private static final String RETRIES = "jdk.httpclient.redirects.retrylimit";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = credentials(TOKEN, ACCOUNT);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void collectLogs() {
		logs.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
	}

	@AfterEach
	void privateDataNeverAppearsInLogs(CapturedOutput output) {
		try {
			assertThat(output.getAll()).doesNotContain(TOKEN, ACCOUNT, ID, RAW_ID, CURSOR, RAW_CURSOR, "private-cursor", PRIVATE,
					"JsonParseException", "StreamReadException", "Unexpected character", "column:");
			assertThat(logs.list).isEmpty();
		}
		finally {
			((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
			logs.stop();
		}
	}

	@Test
	void publicApiAcceptsOnlyTheRequestAndHasNoConfigurableDestination() {
		assertThat(TossOrderClient.class.getConstructors()).hasSize(1);
		assertThat(TossOrderClient.class.getConstructors()[0].getParameterCount()).isZero();
		assertThat(java.util.Arrays.stream(TossOrderClient.class.getDeclaredMethods())
				.filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
				.map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("getOrder", "getClosedOrders", "close");
	}

	@Test
	void closedPageSendsOnlySpecifiedFiltersOnceAndNeverCreatesDateBoundaries() throws Exception {
		List<URI> uris = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			uris.add(exchange.getRequestURI());
			assertThat(exchange.getRequestMethod()).isEqualTo("GET");
			assertThat(exchange.getRequestBody().readAllBytes()).isEmpty();
			assertThat(exchange.getRequestHeaders().get("Accept")).containsExactly("application/json");
			assertThat(exchange.getRequestHeaders().get("Authorization")).containsExactly("Bearer " + TOKEN);
			assertThat(exchange.getRequestHeaders().get("X-Tossinvest-Account")).containsExactly(ACCOUNT);
			reply(exchange, 200, page(null, false));
		})) {
			record QueryCase(TossClosedOrdersRequest request, String raw) { }
			List<QueryCase> cases = List.of(
					new QueryCase(new TossClosedOrdersRequest(), "status=CLOSED&limit=20"),
					new QueryCase(new TossClosedOrdersRequest(null, null, null, null, 1), "status=CLOSED&limit=1"),
					new QueryCase(new TossClosedOrdersRequest(null, null, null, null, 100), "status=CLOSED&limit=100"),
					new QueryCase(new TossClosedOrdersRequest("brK.b-1", null, null, null, null),
							"status=CLOSED&limit=20&symbol=brK%2Eb-1"),
					new QueryCase(new TossClosedOrdersRequest(null, "2026-03-28", null, null, null),
							"status=CLOSED&limit=20&from=2026-03-28"),
					new QueryCase(new TossClosedOrdersRequest(null, null, "2026-03-28", null, null),
							"status=CLOSED&limit=20&to=2026-03-28"),
					new QueryCase(new TossClosedOrdersRequest(null, "2026-03-28", "2026-03-28", null, null),
							"status=CLOSED&limit=20&from=2026-03-28&to=2026-03-28"),
					new QueryCase(new TossClosedOrdersRequest("aApL", "2026-03-01", "2026-03-28", CURSOR, 100),
							"status=CLOSED&limit=100&symbol=aApL&from=2026-03-01&to=2026-03-28&cursor=" + RAW_CURSOR));
			var client = server.client();
			for (QueryCase entry : cases) {
				int before = uris.size();
				assertThat(client.getClosedOrders(entry.request()).orders()).isEmpty();
				assertThat(uris).hasSize(before + 1);
				URI uri = uris.getLast();
				assertThat(uri.getRawPath()).isEqualTo("/api/v1/orders");
				assertThat(uri.getRawQuery()).isEqualTo(entry.raw());
				assertThat(uri.getRawFragment()).isNull();
				if (entry.request().symbol() != null) {
					String raw = java.util.Arrays.stream(uri.getRawQuery().split("&"))
							.filter(pair -> pair.startsWith("symbol=")).findFirst().orElseThrow().substring(7);
					assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).isEqualTo(entry.request().symbol());
				}
			}
		}
	}

	@Test
	void opaqueCursorSurvivesRawQueryWithoutDecodingOrInjectingParameters() throws Exception {
		List<URI> uris = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			uris.add(exchange.getRequestURI());
			reply(exchange, 200, page(null, false));
		})) {
			var client = server.client();
			Map<String, String> cases = Map.ofEntries(Map.entry(CURSOR, RAW_CURSOR),
					Map.entry("%2F", "%252F"), Map.entry("/", "%2F"), Map.entry("+", "%2B"),
					Map.entry("=", "%3D"), Map.entry("%", "%25"), Map.entry(" x ", "%20x%20"),
					Map.entry("한😀", "%ED%95%9C%F0%9F%98%80"),
					Map.entry("&status=OPEN?limit=1#fragment", "%26status%3DOPEN%3Flimit%3D1%23fragment"));
			for (var entry : cases.entrySet()) {
				int before = uris.size();
				client.getClosedOrders(new TossClosedOrdersRequest(null, null, null, entry.getKey(), null));
				assertThat(uris).hasSize(before + 1);
				URI uri = uris.getLast();
				assertThat(uri.getRawPath()).isEqualTo("/api/v1/orders");
				assertThat(uri.getRawFragment()).isNull();
				assertThat(uri.getRawQuery()).isEqualTo("status=CLOSED&limit=20&cursor=" + entry.getValue());
				String[] pairs = uri.getRawQuery().split("&");
				assertThat(pairs).hasSize(3);
				assertThat(URLDecoder.decode(pairs[2].substring("cursor=".length()), StandardCharsets.UTF_8))
						.isEqualTo(entry.getKey());
			}
		}
	}

	@Test
	void mapsOneShortPageWithNextCursorAndEmptyLastPageWithoutAdditionalRequests() throws Exception {
		String domestic = fixture("domestic");
		String partial = fixture("us-partial").replace("005930", "AAPL").replace("KRW", "USD")
				.replace("FILLED", "PARTIAL_FILLED").replace("69999.5000", "0.1234567890123456789000")
				.replace("699995.0000", "0.3703703670370370367000")
				.replace("\"filledQuantity\":\"10.00\"", "\"filledQuantity\":\"3.00\"")
				.replace("2026-03-28T09:30:00.123456789+09:00", "2026-07-01T09:30:00-04:00");
		String canceled = partial.replace("us-partial", "canceled").replace("PARTIAL_FILLED", "CANCELED");
		AtomicReference<String> json = new AtomicReference<>(page("\"" + CURSOR + "\"", true, domestic, partial, canceled));
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, json.get());
		})) {
			var client = server.client();
			var result = client.getClosedOrders(new TossClosedOrdersRequest());
			assertThat(calls).hasValue(1);
			assertThat(result.nextCursor()).isEqualTo(CURSOR);
			assertThat(result.hasNext()).isTrue();
			assertThat(result.orders()).extracting(TossOrderSnapshot::orderId)
					.containsExactly("domestic", "us-partial", "canceled");
			var kr = result.orders().getFirst();
			assertThat(kr.status()).isEqualTo("FILLED");
			assertThat(kr.price()).isEqualTo("70000.00");
			assertThat(kr.quantity()).isEqualTo("10.00");
			assertThat(kr.orderAmount()).isEqualTo("900.00");
			assertThat(kr.orderedAt()).isEqualTo(Instant.parse("2026-03-28T00:30:00.123456789Z"));
			assertThat(kr.execution().averageFilledPrice()).isEqualTo("69999.5000");
			assertThat(kr.execution().filledAmount()).isEqualTo("699995.0000");
			assertThat(kr.execution().commission()).isEqualTo("1400.00");
			assertThat(kr.execution().tax()).isEqualTo("12.30");
			assertThat(kr.execution().filledAt()).isEqualTo(Instant.parse("2026-03-28T00:31:15Z"));
			assertThat(kr.execution().settlementDate()).isEqualTo(LocalDate.of(2026, 3, 30));
			var us = result.orders().get(1);
			assertThat(us.symbol()).isEqualTo("AAPL");
			assertThat(us.currency()).isEqualTo("USD");
			assertThat(us.status()).isEqualTo("PARTIAL_FILLED");
			assertThat(us.orderedAt()).isEqualTo(Instant.parse("2026-07-01T13:30:00Z"));
			assertThat(us.execution().filledQuantity()).isEqualTo("3.00");
			assertThat(us.execution().averageFilledPrice()).isEqualTo("0.1234567890123456789000");
			assertThat(us.execution().filledAmount()).isEqualTo("0.3703703670370370367000");
			assertThat(result.orders().getLast().status()).isEqualTo("CANCELED");
			assertThat(result.orders().getLast().execution()).isEqualTo(us.execution());
			assertThat(result.orders().getLast().canceledAt()).isEqualTo(Instant.parse("2026-03-28T00:32:00Z"));
			json.set(page(null, false));
			var last = client.getClosedOrders(new TossClosedOrdersRequest(null, null, null, result.nextCursor(), null));
			assertThat(last.orders()).isEmpty();
			assertThat(last.nextCursor()).isNull();
			assertThat(last.hasNext()).isFalse();
			assertThat(calls).hasValue(2);
		}
	}

	@ParameterizedTest
	@CsvSource({"1", "20", "100"})
	void closedPageEnforcesRequestedLimitWithoutTruncating(int limit) throws Exception {
		String[] orders = java.util.stream.IntStream.range(0, limit).mapToObj(i -> fixture("order-" + i)).toArray(String[]::new);
		AtomicReference<String> json = new AtomicReference<>(page(null, false, orders));
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, json.get());
		})) {
			var client = server.client();
			var request = new TossClosedOrdersRequest(null, null, null, null, limit == 20 ? null : limit);
			assertThat(client.getClosedOrders(request).orders()).hasSize(limit);
			String[] excessive = java.util.Arrays.copyOf(orders, limit + 1);
			excessive[limit] = fixture("extra");
			json.set(page(null, false, excessive));
			expect(TossErrorCode.INVALID_RESPONSE, () -> client.getClosedOrders(request));
			assertThat(calls).hasValue(2);
		}
	}

	@Test
	void invalidClosedPageJsonMetadataLastItemAndUtf8FailWithoutPartialResults() throws Exception {
		String valid = page(null, false, fixture(ID));
		List<String> invalid = List.of("null", "[]", "{}", "{\"error\":\"" + PRIVATE + "\"}",
				page(null, false, fixture(ID), fixture(ID)),
				page(null, false, fixture(ID), fixture("damaged").replace("\"tax\":\"12.30\"", "\"tax\":false")),
				page("\"" + CURSOR + "\"", true, fixture(ID), fixture(PRIVATE).replace("\"quantity\":\"10.00\"", "\"quantity\":false")),
				page(null, true, fixture(ID)), page("\"next\"", false, fixture(ID)),
				page("\" \"", true, fixture(ID)), valid.replace("\"hasNext\":false", "\"hasNext\":0"),
				valid.replace("\"nextCursor\":null,", ""), valid.replace(",\"hasNext\":false", ""),
				valid.replace("\"orders\":[", "\"orders\":[null,"),
				valid.replace("\"tax\":", "\"tax\":null,\"tax\":"),
				valid + " {}", valid + PRIVATE, valid.substring(0, valid.length() - 1));
		AtomicReference<byte[]> body = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(200, body.get().length);
			try (var stream = exchange.getResponseBody()) { stream.write(body.get()); }
		})) {
			var client = server.client();
			for (String json : invalid) {
				body.set(json.getBytes(StandardCharsets.UTF_8));
				expect(TossErrorCode.INVALID_RESPONSE, () -> client.getClosedOrders(new TossClosedOrdersRequest()));
			}
			for (byte[] utf8 : List.of(new byte[] {(byte) 0xc3, 0x28},
					new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[] {(byte) 0xf0, (byte) 0x9f})) {
				body.set(utf8);
				expect(TossErrorCode.INVALID_RESPONSE, () -> client.getClosedOrders(new TossClosedOrdersRequest()));
			}
			assertThat(calls).hasValue(invalid.size() + 3);
		}
	}

	@Test
	void encodesOpaqueIdsAsOneSegmentWithoutTrimmingDecodingOrChangingCase() throws Exception {
		AtomicReference<String> id = new AtomicReference<>();
		List<URI> uris = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			uris.add(exchange.getRequestURI());
			assertThat(exchange.getRequestMethod()).isEqualTo("GET");
			assertThat(exchange.getRequestBody().readAllBytes()).isEmpty();
			assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
			assertThat(exchange.getRequestHeaders().getFirst("X-Tossinvest-Account")).isEqualTo(ACCOUNT);
			reply(exchange, 200, fixture(id.get()));
		})) {
			var client = server.client();
			Map<String, String> cases = Map.ofEntries(Map.entry(ID, RAW_ID), Map.entry("%2F", "%252F"),
					Map.entry("/", "%2F"), Map.entry("+", "%2B"), Map.entry(" x ", "%20x%20"),
					Map.entry("?", "%3F"), Map.entry("#", "%23"), Map.entry("한😀", "%ED%95%9C%F0%9F%98%80"),
					Map.entry(".", "%2E"), Map.entry("..", "%2E%2E"), Map.entry("../x", "%2E%2E%2Fx"),
					Map.entry("aA0-_~", "aA0-_~"));
			for (var entry : cases.entrySet()) {
				id.set(entry.getKey());
				int before = uris.size();
				assertThat(client.getOrder(new TossOrderRequest(id.get())).orderId()).isEqualTo(id.get());
				assertThat(uris).hasSize(before + 1);
				assertThat(uris.getLast().getRawPath()).isEqualTo(PATH + entry.getValue());
				assertThat(uris.getLast().getRawQuery()).isNull();
				assertThat(uris.getLast().getRawFragment()).isNull();
			}
			for (String boundary : List.of("x".repeat(256), "😀".repeat(128))) {
				id.set(boundary);
				assertThat(client.getOrder(new TossOrderRequest(boundary)).orderId()).isEqualTo(boundary);
			}
			assertThat(uris).hasSize(cases.size() + 2);
		}
	}

	@Test
	void rejectsNullAndUnpairedSurrogatesBeforeEnvironmentOrTransport() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, fixture(ID));
		})) {
			var client = server.client(() -> { reads.incrementAndGet(); return KEYS; });
			expect(TossErrorCode.BAD_REQUEST, () -> client.getOrder(null));
			expect(TossErrorCode.BAD_REQUEST, () -> client.getClosedOrders(null));
			for (String id : List.of("\ud800", "\udc00", "x\ud800y", "\ud800\ud800", "😀\udc00")) {
				expect(TossErrorCode.BAD_REQUEST, () -> client.getOrder(new TossOrderRequest(id)));
			}
			assertThat(reads).hasValue(0);
			assertThat(calls).hasValue(0);
		}
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void rereadsEnvironmentAndPreservesCanonicalInt64HeadersExactly(Lookup lookup) throws Exception {
		AtomicInteger reads = new AtomicInteger();
		AtomicReference<Map<String, String>> values = new AtomicReference<>(KEYS);
		List<String> headers = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			headers.add(exchange.getRequestHeaders().getFirst("Authorization") + "|"
					+ exchange.getRequestHeaders().getFirst("X-Tossinvest-Account"));
			reply(exchange, 200, lookup.response(ID));
		})) {
			var client = server.client(() -> { reads.incrementAndGet(); return values.get(); });
			for (String account : List.of(ACCOUNT, "9223372036854775807", "-9223372036854775808", "-1", "0")) {
				String token = TOKEN + headers.size();
				values.set(credentials(token, account));
				lookup.get(client);
				assertThat(headers.getLast()).isEqualTo("Bearer " + token + "|" + account);
			}
			String boundaryToken = "t".repeat(8192);
			values.set(credentials(boundaryToken, ACCOUNT));
			lookup.get(client);
			assertThat(headers.getLast()).isEqualTo("Bearer " + boundaryToken + "|" + ACCOUNT);
			assertThat(reads).hasValue(6);
			assertThat(headers).hasSize(6);
		}
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void invalidOrMissingCredentialsAndSupplierFailuresNeverSend(Lookup lookup) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		AtomicReference<Map<String, String>> values = new AtomicReference<>();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, lookup.response(ID));
		})) {
			var client = server.client(() -> { reads.incrementAndGet(); return values.get(); });
			for (Map<String, String> missing : List.of(Map.<String, String>of(),
					Map.of("TOSS_ACCESS_TOKEN", TOKEN), Map.of("TOSS_ACCOUNT_SEQ", ACCOUNT),
					credentials("", ACCOUNT), credentials(TOKEN, ""))) {
				values.set(missing);
				expect(TossErrorCode.MISSING_CREDENTIALS, () -> lookup.get(client));
			}
			values.set(null);
			expect(TossErrorCode.MISSING_CREDENTIALS, () -> lookup.get(client));
			for (String invalid : List.of(" ", "a b", "a\tb", "a\rb", "a\nb", "a\u0000b", "a\u007fb",
					"한", "😀", "t".repeat(8193))) {
				values.set(credentials(invalid, ACCOUNT));
				expect(TossErrorCode.INVALID_CREDENTIALS, () -> lookup.get(client));
			}
			for (String invalid : List.of(" ", "01", "-0", "+1", "1.0", "1e2", "１", "١", "1\r\n",
					"9223372036854775808", "-9223372036854775809", "1".repeat(21))) {
				values.set(credentials(TOKEN, invalid));
				expect(TossErrorCode.INVALID_CREDENTIALS, () -> lookup.get(client));
			}
			assertThat(reads).hasValue(28);
			var failing = server.client(() -> { throw new IllegalStateException(TOKEN + ACCOUNT + PRIVATE); });
			expect(TossErrorCode.MISSING_CREDENTIALS, () -> lookup.get(failing));
			assertThat(calls).hasValue(0);
		}
	}

	@Test
	void mapsDomesticUsPartialUnfilledRejectedAndCanceledOrdersThroughExistingMapper() throws Exception {
		AtomicReference<String> json = new AtomicReference<>(fixture(ID));
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, json.get());
		})) {
			var client = server.client();
			var domestic = client.getOrder(request());
			assertThat(domestic.symbol()).isEqualTo("005930");
			assertThat(domestic.status()).isEqualTo("FILLED");
			assertThat(domestic.quantity()).isEqualTo("10.00");
			assertThat(domestic.price()).isEqualTo("70000.00");
			assertThat(domestic.orderAmount()).isEqualTo("900.00");
			assertThat(domestic.orderedAt()).isEqualTo(Instant.parse("2026-03-28T00:30:00.123456789Z"));
			assertThat(domestic.execution().filledQuantity()).isEqualTo("10.00");
			assertThat(domestic.execution().averageFilledPrice()).isEqualTo("69999.5000");
			assertThat(domestic.execution().filledAmount()).isEqualTo("699995.0000");
			assertThat(domestic.execution().commission()).isEqualTo("1400.00");
			assertThat(domestic.execution().tax()).isEqualTo("12.30");
			assertThat(domestic.execution().filledAt()).isEqualTo(Instant.parse("2026-03-28T00:31:15Z"));
			assertThat(domestic.execution().settlementDate()).isEqualTo(LocalDate.of(2026, 3, 30));
			String partial = fixture(ID).replace("005930", "AAPL").replace("KRW", "USD")
					.replace("FILLED", "PARTIAL_FILLED").replace("\"price\":\"70000.00\"", "\"price\":null")
					.replace("\"orderAmount\":\"900.00\"", "\"orderAmount\":null")
					.replace("69999.5000", "0.1234567890123456789000").replace("699995.0000", "0.3703703670370370367000")
					.replace("\"filledQuantity\":\"10.00\"", "\"filledQuantity\":\"3.00\"")
					.replace("\"settlementDate\":\"2026-03-30\"", "\"settlementDate\":null")
					.replace("2026-03-28T09:30:00.123456789+09:00", "2026-07-01T09:30:00-04:00");
			json.set(partial);
			var us = client.getOrder(request());
			assertThat(us.symbol()).isEqualTo("AAPL");
			assertThat(us.currency()).isEqualTo("USD");
			assertThat(us.status()).isEqualTo("PARTIAL_FILLED");
			assertThat(us.price()).isNull();
			assertThat(us.orderAmount()).isNull();
			assertThat(us.orderedAt()).isEqualTo(Instant.parse("2026-07-01T13:30:00Z"));
			assertThat(us.execution().averageFilledPrice()).isEqualTo("0.1234567890123456789000");
			assertThat(us.execution().filledAmount()).isEqualTo("0.3703703670370370367000");
			assertThat(us.execution().settlementDate()).isNull();
			String rejected = """
					{"result":{"orderId":"%s","symbol":"AAPL","side":"SELL","orderType":"MARKET",
					"timeInForce":"DAY","status":"REJECTED","quantity":"5.00","currency":"USD",
					"orderedAt":"2026-07-01T13:30:00Z","execution":{"filledQuantity":"0",
					"averageFilledPrice":null,"filledAmount":null,"commission":null,"tax":null,
					"filledAt":null,"settlementDate":null}}}
					""".formatted(ID);
			json.set(rejected);
			var unfilled = client.getOrder(request());
			assertThat(unfilled.status()).isEqualTo("REJECTED");
			assertThat(unfilled.canceledAt()).isNull();
			assertThat(unfilled.execution()).isEqualTo(new io.github.lmj.tradeledger.application.model.TossOrderSnapshot.ExecutionSummary(
					"0", null, null, null, null, null, null));
			json.set(partial.replace("PARTIAL_FILLED", "CANCELED"));
			var canceled = client.getOrder(request());
			assertThat(canceled.status()).isEqualTo("CANCELED");
			assertThat(canceled.execution()).isEqualTo(us.execution());
			assertThat(canceled.canceledAt()).isEqualTo(Instant.parse("2026-03-28T00:32:00Z"));
			assertThat(calls).hasValue(4);
		}
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void classifiesOnlyHttpStatusAndNeverFollowsRedirectsOrParsesErrorMessages(Lookup lookup) throws Exception {
		AtomicInteger forwarded = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger status = new AtomicInteger();
		AtomicReference<byte[]> body = new AtomicReference<>();
		try (LocalServer target = new LocalServer(exchange -> {
			forwarded.incrementAndGet();
			reply(exchange, 200, lookup.response(ID));
		}); LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.getResponseHeaders().set("Location", target.uri().toString());
			exchange.getResponseHeaders().set("X-Error", PRIVATE + TOKEN + ACCOUNT);
			exchange.sendResponseHeaders(status.get(), status.get() == 204 ? -1 : body.get().length);
			try (var stream = exchange.getResponseBody()) {
				if (status.get() != 204) { stream.write(body.get()); }
			}
		})) {
			var client = server.client();
			Map<Integer, TossErrorCode> cases = Map.ofEntries(Map.entry(400, TossErrorCode.BAD_REQUEST),
					Map.entry(401, TossErrorCode.AUTHENTICATION_FAILED), Map.entry(403, TossErrorCode.PERMISSION_DENIED),
					Map.entry(404, TossErrorCode.ORDER_NOT_FOUND), Map.entry(429, TossErrorCode.RATE_LIMITED),
					Map.entry(300, TossErrorCode.REDIRECT_REJECTED), Map.entry(302, TossErrorCode.REDIRECT_REJECTED),
					Map.entry(307, TossErrorCode.REDIRECT_REJECTED), Map.entry(399, TossErrorCode.REDIRECT_REJECTED),
					Map.entry(500, TossErrorCode.SERVER_ERROR), Map.entry(503, TossErrorCode.SERVER_ERROR),
					Map.entry(599, TossErrorCode.SERVER_ERROR), Map.entry(204, TossErrorCode.UNEXPECTED_STATUS),
					Map.entry(201, TossErrorCode.UNEXPECTED_STATUS), Map.entry(418, TossErrorCode.UNEXPECTED_STATUS));
			for (var entry : cases.entrySet()) {
				status.set(entry.getKey());
				for (byte[] response : List.of(("{\"error\":\"" + PRIVATE + TOKEN + ACCOUNT + ID + CURSOR + "\"}")
						.getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xc3, (byte) 0x28})) {
					body.set(response);
					expect(entry.getValue(), () -> lookup.get(client));
				}
			}
			assertThat(calls).hasValue(cases.size() * 2);
			assertThat(forwarded).hasValue(0);
		}
	}

	@Test
	void invalidSuccessJsonAndUtf8NeverReturnPartialSummaries() throws Exception {
		AtomicReference<byte[]> body = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(200, body.get().length);
			try (var stream = exchange.getResponseBody()) { stream.write(body.get()); }
		})) {
			var client = server.client();
			List<String> invalid = List.of("null", "[]", "{}", "{\"error\":\"" + PRIVATE + "\"}",
					fixture(ID).replace(ID, PRIVATE), fixture(ID).replace("\"tax\":", "\"tax\":null,\"tax\":"),
					fixture(ID) + " {}", fixture(ID) + " " + PRIVATE,
					fixture(ID).substring(0, fixture(ID).length() - 1),
					fixture(ID).replace("\"result\":", "\"error\":null,\"result\":"),
					fixture(ID).replace("70000.00", PRIVATE),
					fixture(ID).replace("2026-03-30", PRIVATE));
			for (String json : invalid) {
				body.set(json.getBytes(StandardCharsets.UTF_8));
				expect(TossErrorCode.INVALID_RESPONSE, () -> client.getOrder(request()));
			}
			for (byte[] utf8 : List.of(new byte[] {(byte) 0xc3, 0x28},
					new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[] {(byte) 0xf0, (byte) 0x9f})) {
				body.set(utf8);
				expect(TossErrorCode.INVALID_RESPONSE, () -> client.getOrder(request()));
			}
			assertThat(calls).hasValue(invalid.size() + 3);
		}
	}

	@ParameterizedTest
	@CsvSource({"ORDER,false", "ORDER,true", "CLOSED,false", "CLOSED,true"})
	void boundsActualFixedAndChunkedUtf8BytesAtExactlyOneMiB(Lookup lookup, boolean chunked) throws Exception {
		AtomicInteger size = new AtomicInteger(TossOrderClient.MAX_RESPONSE_BYTES);
		AtomicInteger calls = new AtomicInteger();
		String json = lookup.response("한😀");
		byte[] prefix = json.getBytes(StandardCharsets.UTF_8);
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(200, chunked ? 0 : size.get());
			try (var stream = exchange.getResponseBody()) {
				stream.write(prefix);
				byte[] spaces = " ".repeat(4096).getBytes(StandardCharsets.US_ASCII);
				int remaining = size.get() - prefix.length;
				while (remaining > 0) {
					int length = Math.min(remaining, spaces.length);
					stream.write(spaces, 0, length);
					remaining -= length;
				}
			}
		})) {
			var client = server.client();
			assertThat(lookup.get(client, "한😀").orderId()).isEqualTo("한😀");
			size.incrementAndGet();
			expect(TossErrorCode.RESPONSE_TOO_LARGE, () -> lookup.get(client, "한😀"));
			assertThat(calls).hasValue(2);
		}
	}

	@Test
	void exceedingBodyLimitCancelsSubscriptionAndLateSubscriptionsStayCanceled() {
		var body = new TossOrderClient.BoundedBodySubscriber();
		AtomicInteger canceled = new AtomicInteger();
		Flow.Subscription subscription = new Flow.Subscription() {
			public void request(long count) { assertThat(count).isEqualTo(1); }
			public void cancel() { canceled.incrementAndGet(); }
		};
		body.onSubscribe(subscription);
		body.onNext(List.of(ByteBuffer.allocate(TossOrderClient.MAX_RESPONSE_BYTES)));
		assertThat(canceled).hasValue(0);
		body.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
		assertThat(canceled).hasValue(1);
		body.onComplete();
		assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
				.hasCauseInstanceOf(TossApiException.class);
		body.onSubscribe(subscription);
		assertThat(canceled).hasValue(2);
		var earlyCancel = new TossOrderClient.BoundedBodySubscriber();
		earlyCancel.cancel();
		earlyCancel.onSubscribe(subscription);
		assertThat(canceled).hasValue(3);
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void oversizedChunkClosesActualConnectionBeforeClientClose(Lookup lookup) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new TossOrderClient(uri(server), () -> KEYS)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(3000);
					var input = reader(socket);
					readRequest(input, calls, lookup);
					var stream = socket.getOutputStream();
					stream.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					int length = TossOrderClient.MAX_RESPONSE_BYTES + 1;
					stream.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
					stream.write(" ".repeat(length).getBytes(StandardCharsets.US_ASCII));
					stream.flush(); // Deliberately leave the response unfinished.
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			expect(TossErrorCode.RESPONSE_TOO_LARGE, () -> lookup.get(client));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);
		}
	}

	@ParameterizedTest
	@CsvSource({"ORDER,false", "ORDER,true", "CLOSED,false", "CLOSED,true"})
	void wholeBodyDeadlineClosesDelayedOrUnfinishedStream(Lookup lookup, boolean partialBody) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new TossOrderClient(uri(server), () -> KEYS)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(15000);
					var input = reader(socket);
					readRequest(input, calls, lookup);
					socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
							+ (partialBody ? "1\r\n{\r\n" : "")).getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			assertThat(TossOrderClient.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
			assertThat(TossOrderClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
			long started = System.nanoTime();
			expect(TossErrorCode.TIMEOUT, () -> lookup.get(client));
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);
		}
	}

	@ParameterizedTest
	@CsvSource({"ORDER,false", "ORDER,true", "CLOSED,false", "CLOSED,true"})
	void neverResendsOnDisconnectBeforeHeadersOnFreshOrReusedConnection(Lookup lookup, boolean reused) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var receiver = executor.submit(() -> {
				while (!server.isClosed()) {
					try (var socket = server.accept()) {
						socket.setSoTimeout(3000);
						var input = reader(socket);
						if (!readRequest(input, calls, lookup)) { continue; }
						if (reused && calls.get() == 1) {
							byte[] json = lookup.response(ID).getBytes(StandardCharsets.UTF_8);
							socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + json.length + "\r\n\r\n")
									.getBytes(StandardCharsets.US_ASCII));
							socket.getOutputStream().write(json);
							socket.getOutputStream().flush();
							assertThat(readRequest(input, calls, lookup)).isTrue();
						}
						// Close before any response headers; count any unexpected retry too.
					}
					catch (java.net.SocketException exception) {
						if (!server.isClosed()) { throw exception; }
					}
				}
				return null;
			});
			try (var client = new TossOrderClient(uri(server), () -> KEYS)) {
				if (reused) {
					assertThat(lookup.get(client).orderId()).isEqualTo(ID);
					assertThat(calls).hasValue(1);
				}
				int before = calls.get();
				expect(TossErrorCode.NETWORK_ERROR, () -> lookup.get(client));
				assertThat(calls.get() - before).isEqualTo(1);
			}
			finally { server.close(); }
			receiver.get(3, TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@CsvSource({"ORDER,false", "ORDER,true", "CLOSED,false", "CLOSED,true"})
	void interruptOrClientCloseCancelsWaitAndActualConnection(Lookup lookup, boolean closeClient) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch headers = new CountDownLatch(1);
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new TossOrderClient(uri(server), () -> KEYS)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(3000);
					var input = reader(socket);
					readRequest(input, calls, lookup);
					socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n"
							.getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
					headers.countDown();
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread worker = Thread.ofVirtual().start(() -> {
				try {
					expect(closeClient ? TossErrorCode.NETWORK_ERROR : TossErrorCode.INTERRUPTED,
							() -> lookup.get(client));
					assertThat(Thread.currentThread().isInterrupted()).isEqualTo(!closeClient);
				}
				catch (Throwable exception) { failure.set(exception); }
			});
			try {
				assertThat(headers.await(3, TimeUnit.SECONDS)).isTrue();
				if (closeClient) { client.close(); }
				else { worker.interrupt(); }
				worker.join(3000);
				assertThat(worker.isAlive()).isFalse();
				assertThat(failure.get()).isNull();
				receiver.get(3, TimeUnit.SECONDS);
				assertThat(calls).hasValue(1);
			}
			finally { worker.interrupt(); }
		}
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void connectionFailureIsSanitized(Lookup lookup) throws Exception {
		URI unavailable;
		try (ServerSocket socket = loopbackSocket()) { unavailable = uri(socket); }
		try (var client = new TossOrderClient(unavailable, () -> KEYS)) {
			expect(TossErrorCode.NETWORK_ERROR, () -> lookup.get(client));
		}
	}

	@ParameterizedTest
	@CsvSource({"ORDER,false", "ORDER,true", "CLOSED,false", "CLOSED,true"})
	void disconnectDuringFixedOrChunkedBodyIsSanitizedWithoutResending(Lookup lookup, boolean chunked) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new TossOrderClient(uri(server), () -> KEYS)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(3000);
					readRequest(reader(socket), calls, lookup);
					String headers = chunked ? "Transfer-Encoding: chunked\r\n\r\n1000\r\n"
							: "Content-Length: 4096\r\n\r\n";
					socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + headers + PRIVATE)
							.getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
					// Close before the promised bytes and, for chunked encoding, before the final chunk.
				}
				return null;
			});
			expect(TossErrorCode.NETWORK_ERROR, () -> lookup.get(client));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);
		}
	}

	@Test
	void testSeamAllowsOnlyLiteralLoopbackHttpAndFixedPrefix() {
		for (String endpoint : List.of("https://openapi.tossinvest.com" + PATH, "http://example.com" + PATH,
				"http://localhost" + PATH, "https://127.0.0.1" + PATH, "http://127.0.0.2" + PATH,
				"http://127.0.0.1/other/", "http://127.0.0.1/api/v1/orders", "http://127.0.0.1" + PATH + "id",
				"http://127.0.0.1/api/v1/%6Frders/", "http://127.0.0.1" + PATH + "?a=1",
				"http://127.0.0.1" + PATH + "#x", "http://user@127.0.0.1" + PATH)) {
			expect(TossErrorCode.BAD_REQUEST, () -> new TossOrderClient(URI.create(endpoint), () -> KEYS));
		}
		expect(TossErrorCode.BAD_REQUEST, () -> new TossOrderClient(null, () -> KEYS));
		expect(TossErrorCode.BAD_REQUEST, () -> new TossOrderClient(URI.create("http://127.0.0.1" + PATH), null));
		try (var ipv6 = new TossOrderClient(URI.create("http://[::1]:12345" + PATH), () -> KEYS)) {
			assertThat(ipv6).isNotNull();
		}
	}

	@ParameterizedTest
	@EnumSource(Lookup.class)
	void independentJvmsRejectUnsafeStartupAndLateSettingsBeforeEnvironmentOrSending(Lookup lookup) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, lookup.response(ID));
		})) {
			List<List<String>> invalid = List.of(List.of(), List.of("-D" + DISABLE + "=true"),
					List.of("-D" + RETRIES + "=1"), List.of("-D" + DISABLE + "=false", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=5"),
					List.of("-D" + DISABLE + "=false", "-D" + DISABLE + "=true", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=5", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE, "-D" + DISABLE + "=true", "-D" + RETRIES + "=1"));
			for (List<String> options : invalid) {
				assertThat(runProbe(server.uri(), options, "blocked", lookup)).isEqualTo("BLOCKED\n");
				assertThat(calls).hasValue(0);
			}
			List<String> valid = List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=1");
			for (String mode : List.of("mutated", "lateLogging", "beforeSend", "loggingBeforeSend")) {
				assertThat(runProbe(server.uri(), valid, mode, lookup)).isEqualTo("BLOCKED\n");
				assertThat(calls).hasValue(0);
			}
			List<String> logging = new ArrayList<>(valid);
			logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
			assertThat(runProbe(server.uri(), logging, "logging", lookup)).isEqualTo("BLOCKED\n");
			assertThat(runProbe(server.uri(), logging, "clearedLogging", lookup)).isEqualTo("BLOCKED\n");
			logging.add("-D" + HTTP_LOG + "=");
			assertThat(runProbe(server.uri(), logging, "logging", lookup)).isEqualTo("BLOCKED\n");
			assertThat(calls).hasValue(0);
			assertThat(runProbe(server.uri(), valid, "success", lookup)).isEqualTo("SUCCESS\n");
			assertThat(calls).hasValue(1);
			List<String> emptyLogging = new ArrayList<>(valid);
			emptyLogging.add("-D" + HTTP_LOG + "=");
			assertThat(runProbe(server.uri(), emptyLogging, "success", lookup)).isEqualTo("SUCCESS\n");
			assertThat(calls).hasValue(2);
		}
	}

	private static String runProbe(URI endpoint, List<String> options, String mode, Lookup lookup) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).isNotBlank();
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Xint"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, StartupProbe.class.getName(), endpoint.toString(), mode, lookup.name()));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
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
					assertThat(output.size()).isLessThanOrEqualTo(8192);
				}
				if (exited) { break; }
				assertThat(System.nanoTime() < deadline).as("Child JVM deadline").isTrue();
				exited = process.waitFor(25, TimeUnit.MILLISECONDS);
			}
			assertThat(process.exitValue()).as("Child output: %s", output.toString(StandardCharsets.UTF_8)).isZero();
			return output.toString(StandardCharsets.UTF_8);
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	/** Isolated JVM using fake credentials and a literal loopback endpoint only. */
	public static final class StartupProbe {
		public static void main(String[] args) {
			try {
				URI endpoint = URI.create(args[0]);
				String mode = args[1];
				Lookup lookup = Lookup.valueOf(args[2]);
				AtomicInteger reads = new AtomicInteger();
				Supplier<Map<String, String>> environment = () -> { reads.incrementAndGet(); return KEYS; };
				if (mode.equals("success")) {
					try (var client = new TossOrderClient(endpoint, environment)) {
						if (!lookup.get(client).orderId().equals(ID) || reads.get() != 1) { throw new AssertionError(); }
					}
					System.out.println("SUCCESS");
					return;
				}
				if (mode.equals("mutated") || mode.equals("lateLogging")) {
					try (var client = new TossOrderClient(endpoint, environment)) {
						System.setProperty(mode.equals("mutated") ? RETRIES : HTTP_LOG,
								mode.equals("mutated") ? "2" : "requests,headers,content,errors");
						assertBlocked(() -> lookup.get(client));
					}
				}
				else if (mode.equals("beforeSend") || mode.equals("loggingBeforeSend")) {
					// Mutation during environment lookup must still be caught before send.
					try (var client = new TossOrderClient(endpoint, () -> {
						reads.incrementAndGet();
						System.setProperty(mode.equals("beforeSend") ? RETRIES : HTTP_LOG,
								mode.equals("beforeSend") ? "2" : "requests,headers");
						return KEYS;
					})) {
						assertBlocked(() -> lookup.get(client));
					}
					if (reads.get() != 1) { throw new AssertionError(); }
					System.out.println("BLOCKED");
					return;
				}
				else {
					if (mode.equals("clearedLogging")) { System.clearProperty(HTTP_LOG); }
					assertBlocked(() -> { try (var client = new TossOrderClient(endpoint, environment)) {
						lookup.get(client);
					} });
					if (mode.equals("blocked")) {
						System.setProperty(DISABLE, "true");
						System.setProperty(RETRIES, "1");
						assertBlocked(() -> { try (var client = new TossOrderClient(endpoint, environment)) {
							lookup.get(client);
						} });
					}
				}
				if (reads.get() != 0) { throw new AssertionError(); }
				System.out.println("BLOCKED");
			}
			catch (Throwable failure) {
				System.err.println("PROBE_FAILED:" + failure.getClass().getSimpleName());
				System.exit(1);
			}
		}

		private static void assertBlocked(Runnable action) {
			try { action.run(); }
			catch (TossApiException exception) {
				if (exception.code() == TossErrorCode.UNSAFE_TRANSPORT_CONFIGURATION
						&& exception.getCause() == null && exception.getSuppressed().length == 0) { return; }
			}
			throw new AssertionError("Unsafe transport accepted");
		}
	}

	private enum Lookup {
		ORDER, CLOSED;

		TossOrderSnapshot get(TossOrderClient client) { return get(client, ID); }

		TossOrderSnapshot get(TossOrderClient client, String id) {
			return this == ORDER ? client.getOrder(new TossOrderRequest(id))
					: client.getClosedOrders(new TossClosedOrdersRequest(null, null, null, CURSOR, null)).orders().getFirst();
		}

		String response(String id) { return this == ORDER ? fixture(id) : page(null, false, fixture(id)); }

		String target() {
			return this == ORDER ? PATH + RAW_ID : "/api/v1/orders?status=CLOSED&limit=20&cursor=" + RAW_CURSOR;
		}
	}

	private static String page(String cursorJson, boolean hasNext, String... wrappedOrders) {
		List<String> orders = java.util.Arrays.stream(wrappedOrders)
				.map(json -> json.substring("{\"result\":".length(), json.length() - 1)).toList();
		return "{\"result\":{\"orders\":[" + String.join(",", orders) + "],\"nextCursor\":"
				+ cursorJson + ",\"hasNext\":" + hasNext + "}}";
	}

	private static Map<String, String> credentials(String token, String account) {
		return Map.of("TOSS_ACCESS_TOKEN", token, "TOSS_ACCOUNT_SEQ", account);
	}

	private static TossOrderRequest request() { return new TossOrderRequest(ID); }

	private static ServerSocket loopbackSocket() throws IOException {
		return new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
	}

	private static URI uri(ServerSocket socket) {
		return URI.create("http://127.0.0.1:" + socket.getLocalPort() + PATH);
	}

	private static BufferedReader reader(java.net.Socket socket) throws IOException {
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
	}

	private static boolean readRequest(BufferedReader input, AtomicInteger calls, Lookup lookup) throws IOException {
		String line = input.readLine();
		if (line == null) { return false; }
		assertThat(line).isEqualTo("GET " + lookup.target() + " HTTP/1.1");
		calls.incrementAndGet();
		while ((line = input.readLine()) != null && !line.isEmpty()) { }
		return true;
	}

	private static void expect(TossErrorCode code, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(TossApiException.class, exception -> {
			assertThat(exception.code()).isEqualTo(code);
			assertThat(exception.getMessage()).isEqualTo("Toss request failed: " + code.name());
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			StringWriter trace = new StringWriter();
			exception.printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain(TOKEN, ACCOUNT, ID, RAW_ID, CURSOR, RAW_CURSOR, "private-cursor", PRIVATE,
					"http://", "https://", "tools.jackson", "Unexpected character", "column:");
			exception.addSuppressed(new IllegalStateException(PRIVATE));
			assertThat(exception.getSuppressed()).isEmpty();
			assertThatThrownBy(() -> exception.initCause(new IllegalStateException(TOKEN)))
					.isInstanceOf(IllegalStateException.class);
			assertThat(exception.getCause()).isNull();
		});
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
	}

	private static String fixture(String id) {
		return """
				{"result":{"orderId":"%s","symbol":"005930","side":"BUY","orderType":"LIMIT",
				"timeInForce":"DAY","status":"FILLED","price":"70000.00","quantity":"10.00",
				"orderAmount":"900.00","currency":"KRW","orderedAt":"2026-03-28T09:30:00.123456789+09:00",
				"canceledAt":"2026-03-28T09:32:00+09:00","execution":{"filledQuantity":"10.00",
				"averageFilledPrice":"69999.5000","filledAmount":"699995.0000","commission":"1400.00",
				"tax":"12.30","filledAt":"2026-03-28T09:31:15+09:00","settlementDate":"2026-03-30"}}}
				""".formatted(id.replace("\\", "\\\\").replace("\"", "\\\"")).stripTrailing();
	}

	private static final class LocalServer implements AutoCloseable {
		private final HttpServer server;
		private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		private final List<TossOrderClient> clients = new ArrayList<>();

		LocalServer(HttpHandler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			// Capture every path, including an accidentally normalized/decoded ID.
			server.createContext("/", handler);
			server.setExecutor(executor);
			server.start();
		}

		URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH); }
		TossOrderClient client() { return client(() -> KEYS); }
		TossOrderClient client(Supplier<Map<String, String>> environment) {
			var client = new TossOrderClient(uri(), environment);
			clients.add(client);
			return client;
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
			clients.forEach(TossOrderClient::close);
		}
	}
}
