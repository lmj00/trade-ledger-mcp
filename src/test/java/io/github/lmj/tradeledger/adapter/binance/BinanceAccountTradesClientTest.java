package io.github.lmj.tradeledger.adapter.binance;

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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BinanceAccountTradesClientTest {
	private static final String KEY = "fake-api-key-marker";
	private static final String SECRET = "fake-secret-key-marker";
	private static final String ALIAS = "private-local-account-marker";
	private static final String PRIVATE = "private-response-marker";
	private static final String PATH = "/api/v3/myTrades";
	private static final String DISABLE = "jdk.httpclient.disableRetryConnect";
	private static final String RETRIES = "jdk.httpclient.redirects.retrylimit";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = Map.of("BINANCE_API_KEY", KEY, "BINANCE_SECRET_KEY", SECRET);
	private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000000123L), ZoneOffset.UTC);
	private static final String FILL = """
			{"symbol":"BTCUSDT","id":9007199254740993,"orderId":9223372036854775807,"orderListId":-1,
			"price":"123.4500","qty":"0.0200","quoteQty":"2.469000","commission":"0.0000100",
			"commissionAsset":"BNB","time":1499865549590,"isBuyer":true,"isMaker":false,"isBestMatch":true}
			""".strip();

	@Test
	void signsExactReceivedBytesAndSendsExactlyOneGetPerCall(CapturedOutput output) throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> headers = new CopyOnWriteArrayList<>();
		List<String> allHeaders = new CopyOnWriteArrayList<>();
		List<String> signatures = new ArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			headers.add(exchange.getRequestHeaders().getFirst("X-MBX-APIKEY"));
			allHeaders.add(exchange.getRequestHeaders().entrySet().toString());
			reply(exchange, 200, "[]");
		})) {
			var client = server.client();
			for (String symbol : List.of("BTCUSDT", "한글😀", "a b/+%&=?#")) {
				for (int limit : new int[] {500, 1, 1000}) {
					int before = requests.size();
					var request = limit == 500 ? new BinanceAccountTradesRequest(symbol)
							: new BinanceAccountTradesRequest(symbol, limit);
					var result = client.getAccountTrades(ALIAS, request);
					assertThat(requests).hasSize(before + 1);
					String target = requests.getLast();
					assertThat(target).startsWith("GET " + PATH + "?symbol=");
					String raw = URI.create(target.substring(4)).getRawQuery();
					int boundary = raw.indexOf("&signature=");
					String query = raw.substring(0, boundary);
					String signature = raw.substring(boundary + "&signature=".length());
					signatures.add(signature);
					assertThat(signature).isEqualTo(hmac(query));
					assertThat(query).endsWith("&limit=" + limit + "&recvWindow=5000&timestamp=1700000000123");
					String encoded = switch (symbol) {
						case "BTCUSDT" -> "BTCUSDT";
						case "한글😀" -> "%ED%95%9C%EA%B8%80%F0%9F%98%80";
						default -> "a%20b%2F%2B%25%26%3D%3F%23";
					};
					assertThat(query).startsWith("symbol=" + encoded + "&");
					assertThat(target).doesNotContain(ALIAS, SECRET, KEY);
					assertThat(result.accountAlias()).isEqualTo(ALIAS);
					assertThat(result.symbol()).isEqualTo(symbol);
					assertThat(result.trades()).isEmpty();
					assertThat(result.possiblyTruncated()).isFalse();
				}
			}
			assertThat(headers).hasSize(9).containsOnly(KEY);
			for (String receivedHeaders : allHeaders) {
				assertThat(receivedHeaders).doesNotContain(ALIAS, SECRET);
			}
		}
		assertSafeOutput(output, signatures);
	}

	@Test
	void preservesMapperValuesAndTruncation() throws Exception {
		AtomicReference<String> body = new AtomicReference<>("[" + FILL + "]");
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, body.get());
		})) {
			var result = server.client().getAccountTrades(ALIAS, new BinanceAccountTradesRequest("BTCUSDT", 1));
			assertThat(result.accountAlias()).isEqualTo(ALIAS);
			assertThat(result.serviceId()).isEqualTo("binance");
			assertThat(result.possiblyTruncated()).isTrue();
			var fill = result.trades().getFirst();
			assertThat(fill.id()).isEqualTo(9007199254740993L);
			assertThat(fill.orderId()).isEqualTo(Long.MAX_VALUE);
			assertThat(fill.orderListId()).isEqualTo(-1);
			assertThat(fill.price()).isEqualTo("123.4500");
			assertThat(fill.qty()).isEqualTo("0.0200");
			assertThat(fill.quoteQty()).isEqualTo("2.469000");
			assertThat(fill.commission()).isEqualTo("0.0000100");
			assertThat(fill.commissionAsset()).isEqualTo("BNB");
			assertThat(fill.time()).isEqualTo(Instant.parse("2017-07-12T13:19:09.590Z"));
			assertThat(fill.isBuyer()).isTrue();
			assertThat(fill.isMaker()).isFalse();
			assertThat(fill.isBestMatch()).isTrue();
			assertThat(calls).hasValue(1);
		}
	}

	@Test
	void rejectsInvalidInputsWithoutSending(CapturedOutput output) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<Map<String, String>> environment = new AtomicReference<>(KEYS);
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, "[]");
		})) {
			var client = server.client(environment::get, CLOCK);
			expect(BinanceErrorCode.BAD_REQUEST, () -> client.getAccountTrades(ALIAS, null));
			for (String alias : new String[] {null, "", " ", "x".repeat(257), "a\n", "\u007f", "\u0085", "\ud800", "\udc00", "a\ud800b"}) {
				expect(BinanceErrorCode.BAD_REQUEST, () -> client.getAccountTrades(alias, request()));
			}
			for (Map<String, String> missing : List.of(Map.<String, String>of(),
					Map.of("BINANCE_API_KEY", KEY), Map.of("BINANCE_SECRET_KEY", SECRET),
					Map.of("BINANCE_API_KEY", " ", "BINANCE_SECRET_KEY", SECRET),
					Map.of("BINANCE_API_KEY", KEY, "BINANCE_SECRET_KEY", ""))) {
				environment.set(missing);
				expect(BinanceErrorCode.MISSING_CREDENTIALS, () -> client.getAccountTrades(ALIAS, request()));
			}
			for (String key : List.of("x\r\n" + PRIVATE, "x\t", "x\u0000", "x\u007f", "한글", "\ud800", " " + KEY)) {
				environment.set(Map.of("BINANCE_API_KEY", key, "BINANCE_SECRET_KEY", SECRET));
				expect(BinanceErrorCode.INVALID_CREDENTIALS, () -> client.getAccountTrades(ALIAS, request()));
			}
			for (String secret : List.of("\ud800", "x\n")) {
				environment.set(Map.of("BINANCE_API_KEY", KEY, "BINANCE_SECRET_KEY", secret));
				expect(BinanceErrorCode.INVALID_CREDENTIALS, () -> client.getAccountTrades(ALIAS, request()));
			}
			expect(BinanceErrorCode.BAD_REQUEST, () -> server.client(() -> KEYS,
					Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC)).getAccountTrades(ALIAS, request()));
			expect(BinanceErrorCode.MISSING_CREDENTIALS, () -> server.client(() -> {
				throw new IllegalStateException(PRIVATE + SECRET);
			}, CLOCK).getAccountTrades(ALIAS, request()));
			assertThat(calls).hasValue(0);
		}
		assertSafeOutput(output, List.of());
	}

	@Test
	void allowsBoundaryAliasesAndReadsEnvironmentOnEveryCall() throws Exception {
		List<String> keys = new CopyOnWriteArrayList<>();
		AtomicReference<Map<String, String>> environment = new AtomicReference<>(KEYS);
		try (LocalServer server = new LocalServer(exchange -> {
			keys.add(exchange.getRequestHeaders().getFirst("X-MBX-APIKEY"));
			reply(exchange, 200, "[]");
		})) {
			var client = server.client(environment::get, CLOCK);
			for (String alias : List.of("x".repeat(256), "😀".repeat(128), " 한글 ")) {
				assertThat(client.getAccountTrades(alias, request()).accountAlias()).isEqualTo(alias);
			}
			environment.set(Map.of("BINANCE_API_KEY", KEY + "2", "BINANCE_SECRET_KEY", SECRET));
			client.getAccountTrades(ALIAS, request());
			assertThat(keys).containsExactly(KEY, KEY, KEY, KEY + "2");
		}
	}

	@Test
	void classifiesStatusesAndNeverFollowsRedirect(CapturedOutput output) throws Exception {
		AtomicInteger status = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger forwarded = new AtomicInteger();
		List<String> signatures = new CopyOnWriteArrayList<>();
		try (LocalServer target = new LocalServer(exchange -> {
			forwarded.incrementAndGet();
			reply(exchange, 200, "[]");
		}); LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			String signature = exchange.getRequestURI().getRawQuery().split("&signature=")[1];
			signatures.add(signature);
			exchange.getResponseHeaders().set("Location", target.uri().toString());
			reply(exchange, status.get(), PRIVATE + KEY + SECRET + signature);
		})) {
			var client = server.client();
			Map<Integer, BinanceErrorCode> cases = Map.ofEntries(
					Map.entry(400, BinanceErrorCode.BAD_REQUEST), Map.entry(401, BinanceErrorCode.AUTHENTICATION_FAILED),
					Map.entry(403, BinanceErrorCode.SECURITY_RESTRICTION), Map.entry(418, BinanceErrorCode.IP_BLOCKED),
					Map.entry(429, BinanceErrorCode.RATE_LIMITED), Map.entry(500, BinanceErrorCode.SERVER_ERROR),
					Map.entry(503, BinanceErrorCode.SERVER_ERROR), Map.entry(599, BinanceErrorCode.SERVER_ERROR),
					Map.entry(302, BinanceErrorCode.REDIRECT_REJECTED), Map.entry(307, BinanceErrorCode.REDIRECT_REJECTED),
					Map.entry(404, BinanceErrorCode.UNEXPECTED_STATUS), Map.entry(204, BinanceErrorCode.UNEXPECTED_STATUS));
			for (var entry : cases.entrySet()) {
				status.set(entry.getKey());
				expect(entry.getValue(), () -> client.getAccountTrades(ALIAS, request()), signatures);
			}
			assertThat(calls).hasValue(cases.size());
			assertThat(forwarded).hasValue(0);
		}
		assertSafeOutput(output, signatures);
	}

	@Test
	void recognizesOnlyValidatedCodesAndFallsBackForDamagedErrorBodies(CapturedOutput output) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger status = new AtomicInteger(400);
		AtomicReference<String> body = new AtomicReference<>();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, status.get(), body.get());
		})) {
			var client = server.client();
			int expected = 0;
			for (int httpStatus : new int[] {400, 401}) {
				status.set(httpStatus);
				for (var entry : Map.of(-1021, BinanceErrorCode.INVALID_TIMESTAMP,
						-1022, BinanceErrorCode.INVALID_SIGNATURE, -2014, BinanceErrorCode.INVALID_API_KEY_FORMAT,
						-2015, BinanceErrorCode.KEY_IP_OR_PERMISSION_REJECTED).entrySet()) {
					body.set("{\"code\":" + entry.getKey() + ",\"msg\":\"" + PRIVATE + KEY + SECRET + "\"}");
					expect(entry.getValue(), () -> client.getAccountTrades(ALIAS, request()));
					expected++;
				}
				for (String invalid : List.of("{", "[]", "null", "{\"code\":-1021,\"code\":-2015}",
						"{\"code\":-1021} {}", "{\"code\":\"-1021\"}", "{\"code\":-1021.0}",
						"{\"code\":-1021,\"msg\":\"" + "x".repeat(257) + "\"}",
						"{\"code\":-1021,\"x\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}",
						"{\"code\":-1021,\"x\":" + "1".repeat(129) + "}",
						"{\"code\":-1021,\"" + "x".repeat(257) + "\":0}",
						"{\"code\":-1021,\"x\":{\"a\":1,\"a\":2}}", "{\"code\":9999,\"msg\":\"-2015\"}",
						"{\"x\":{\"code\":-1021}}", "{\"code\":-1021,\"msg\":}")) {
					body.set(invalid);
					expect(httpStatus == 400 ? BinanceErrorCode.BAD_REQUEST : BinanceErrorCode.AUTHENTICATION_FAILED,
							() -> client.getAccountTrades(ALIAS, request()));
					expected++;
				}
			}
			for (var entry : Map.of(403, BinanceErrorCode.SECURITY_RESTRICTION, 418, BinanceErrorCode.IP_BLOCKED,
					429, BinanceErrorCode.RATE_LIMITED, 500, BinanceErrorCode.SERVER_ERROR).entrySet()) {
				status.set(entry.getKey());
				body.set("{\"code\":-2015}");
				expect(entry.getValue(), () -> client.getAccountTrades(ALIAS, request()));
				expected++;
			}
			assertThat(calls).hasValue(expected);
		}
		assertSafeOutput(output, List.of());
	}

	@Test
	void invalidSuccessNeverReturnsPartialResults(CapturedOutput output) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		List<String> signatures = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			String signature = exchange.getRequestURI().getRawQuery().split("&signature=")[1];
			signatures.add(signature);
			reply(exchange, 200, body.get().replace("MARKER", PRIVATE + KEY + SECRET + signature));
		})) {
			var client = server.client();
			List<String> invalid = List.of("[", "{}", "null", "[\"MARKER\"]", "[" + FILL + ",{}]",
					"[" + FILL + "," + FILL + "]", "[" + FILL.replace("BTCUSDT", "ETHUSDT") + "]",
					"[" + FILL.replace("\"id\":", "\"id\":1,\"id\":") + "]", "[" + FILL + "] {}");
			for (String json : invalid) {
				body.set(json);
				expect(BinanceErrorCode.INVALID_RESPONSE, () -> client.getAccountTrades(ALIAS, request()), signatures);
			}
			body.set("[" + FILL + "," + FILL.replace("9007199254740993", "2") + "]");
			expect(BinanceErrorCode.INVALID_RESPONSE,
					() -> client.getAccountTrades(ALIAS, new BinanceAccountTradesRequest("BTCUSDT", 1)), signatures);
			assertThat(calls).hasValue(invalid.size() + 1);
		}
		assertSafeOutput(output, signatures);
	}

	@Test
	void chunkedBodyAcceptsExactMiBAndRejectsOneExtraByte() throws Exception {
		AtomicInteger size = new AtomicInteger(BinanceAccountTradesClient.MAX_RESPONSE_BYTES);
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(200, 0);
			try (var stream = exchange.getResponseBody()) {
				stream.write("[]".getBytes(StandardCharsets.UTF_8));
				byte[] spaces = " ".repeat(4096).getBytes(StandardCharsets.UTF_8);
				int remaining = size.get() - 2;
				while (remaining > 0) {
					int length = Math.min(remaining, spaces.length);
					stream.write(spaces, 0, length);
					remaining -= length;
				}
			}
		})) {
			var client = server.client();
			assertThat(client.getAccountTrades(ALIAS, request()).trades()).isEmpty();
			size.incrementAndGet();
			expect(BinanceErrorCode.RESPONSE_TOO_LARGE, () -> client.getAccountTrades(ALIAS, request()));
			assertThat(calls).hasValue(2);
		}
	}

	@Test
	void exceedingBodyLimitImmediatelyCancelsSubscription() {
		var body = new BinanceAccountTradesClient.BoundedBodySubscriber();
		AtomicBoolean cancelled = new AtomicBoolean();
		body.onSubscribe(new Flow.Subscription() {
			public void request(long count) { }
			public void cancel() { cancelled.set(true); }
		});
		body.onNext(List.of(ByteBuffer.allocate(BinanceAccountTradesClient.MAX_RESPONSE_BYTES)));
		assertThat(cancelled).isFalse();
		body.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
		assertThat(cancelled).isTrue();
		assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
				.hasCauseInstanceOf(BinanceApiException.class);
	}

	@Test
	void oversizedChunkCancelsTheActualConnectionBeforeClientClose() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket();
				var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new BinanceAccountTradesClient(uri(server), () -> KEYS, CLOCK)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(3000);
					var input = reader(socket);
					readRequest(input, calls);
					var stream = socket.getOutputStream();
					stream.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
							.getBytes(StandardCharsets.US_ASCII));
					int length = BinanceAccountTradesClient.MAX_RESPONSE_BYTES + 1;
					stream.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
					stream.write(" ".repeat(length).getBytes(StandardCharsets.US_ASCII));
					stream.flush(); // Deliberately leave the response incomplete.
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			expect(BinanceErrorCode.RESPONSE_TOO_LARGE, () -> client.getAccountTrades(ALIAS, request()));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);
		}
	}

	@Test
	void malformedUtf8IsInvalidSuccessButFallsBackToHttpStatusForErrors() throws Exception {
		AtomicInteger status = new AtomicInteger(200);
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			byte[] invalid = new byte[] {'[', '"', (byte) 0xc3, '"', ']'};
			exchange.sendResponseHeaders(status.get(), invalid.length);
			try (var stream = exchange.getResponseBody()) { stream.write(invalid); }
		})) {
			var client = server.client();
			expect(BinanceErrorCode.INVALID_RESPONSE, () -> client.getAccountTrades(ALIAS, request()));
			status.set(401);
			expect(BinanceErrorCode.AUTHENTICATION_FAILED, () -> client.getAccountTrades(ALIAS, request()));
			assertThat(calls).hasValue(2);
		}
	}

	@Test
	void wholeBodyDeadlineCancelsConnectionAfterHeaders() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket();
				var executor = Executors.newVirtualThreadPerTaskExecutor();
				var client = new BinanceAccountTradesClient(uri(server), () -> KEYS, CLOCK)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(15000);
					var input = reader(socket);
					readRequest(input, calls);
					socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n[\r\n"
							.getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			assertThat(BinanceAccountTradesClient.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
			assertThat(BinanceAccountTradesClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
			long started = System.nanoTime();
			expect(BinanceErrorCode.TIMEOUT, () -> client.getAccountTrades(ALIAS, request()));
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void neverResendsOnDisconnectBeforeHeadersOnFreshOrReusedConnection(boolean reused) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (ServerSocket server = loopbackSocket(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var receiver = executor.submit(() -> {
				while (!server.isClosed()) {
					try (var socket = server.accept()) {
						socket.setSoTimeout(3000);
						var input = reader(socket);
						if (!readRequest(input, calls)) {
							continue;
						}
						if (reused && calls.get() == 1) {
							socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]"
								.getBytes(StandardCharsets.US_ASCII));
							socket.getOutputStream().flush();
							assertThat(readRequest(input, calls)).isTrue();
						}
						// Close without sending any headers for this call, then accept any retry.
					}
					catch (java.net.SocketException exception) {
						if (!server.isClosed()) {
							throw exception;
						}
					}
				}
				return null;
			});
			try (var client = new BinanceAccountTradesClient(uri(server), () -> KEYS, CLOCK)) {
				if (reused) {
					assertThat(client.getAccountTrades(ALIAS, request()).trades()).isEmpty();
					assertThat(calls).hasValue(1);
				}
				int before = calls.get();
				expect(BinanceErrorCode.NETWORK_ERROR, () -> client.getAccountTrades(ALIAS, request()));
				assertThat(calls.get() - before).isEqualTo(1);
			}
			finally {
				server.close();
			}
			receiver.get(3, TimeUnit.SECONDS);
		}
	}

	@Test
	void sanitizesNetworkFailureAndPreservesInterrupt(CapturedOutput output) throws Exception {
		URI unavailable;
		try (ServerSocket socket = loopbackSocket()) {
			unavailable = uri(socket);
		}
		try (var client = new BinanceAccountTradesClient(unavailable, () -> KEYS, CLOCK)) {
			expect(BinanceErrorCode.NETWORK_ERROR, () -> client.getAccountTrades(ALIAS, request()));
		}
		CountDownLatch headers = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			exchange.sendResponseHeaders(200, 0);
			try (var stream = exchange.getResponseBody()) {
				stream.write('[');
				stream.flush();
				headers.countDown();
				try { release.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			}
		})) {
			var client = server.client();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread worker = Thread.ofVirtual().start(() -> {
				try {
					expect(BinanceErrorCode.INTERRUPTED, () -> client.getAccountTrades(ALIAS, request()));
					assertThat(Thread.currentThread().isInterrupted()).isTrue();
				}
				catch (Throwable exception) { failure.set(exception); }
			});
			try {
				assertThat(headers.await(3, TimeUnit.SECONDS)).isTrue();
				worker.interrupt();
				worker.join(3000);
				assertThat(worker.isAlive()).isFalse();
				assertThat(failure.get()).isNull();
				assertThat(calls).hasValue(1);
			}
			finally { release.countDown(); worker.interrupt(); }
		}
		assertSafeOutput(output, List.of());
	}

	@Test
	void testSeamOnlyAllowsLiteralLoopbackAndFixedPath() {
		for (String endpoint : List.of("https://api.binance.com" + PATH, "http://example.com" + PATH,
				"http://localhost" + PATH, "https://127.0.0.1" + PATH, "http://127.0.0.1/other",
				"http://127.0.0.1" + PATH + "/", "http://127.0.0.1" + PATH + "?a=1",
				"http://127.0.0.1" + PATH + "#x", "http://user@127.0.0.1" + PATH)) {
			expect(BinanceErrorCode.BAD_REQUEST,
					() -> new BinanceAccountTradesClient(URI.create(endpoint), () -> KEYS, CLOCK));
		}
		expect(BinanceErrorCode.BAD_REQUEST, () -> new BinanceAccountTradesClient(null, () -> KEYS, CLOCK));
	}

	@Test
	void independentJvmsRejectMissingWrongConflictingAndLateOptionsBeforeSending() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, "[]");
		})) {
			List<List<String>> invalidOptions = List.of(List.of(), List.of("-D" + DISABLE + "=true"),
					List.of("-D" + DISABLE + "=false", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=5"),
					List.of("-D" + DISABLE + "=false", "-D" + DISABLE + "=true", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=5", "-D" + RETRIES + "=1"),
					List.of("-D" + DISABLE, "-D" + DISABLE + "=true", "-D" + RETRIES + "=1"));
			for (List<String> options : invalidOptions) {
				assertThat(runProbe(server.uri(), options, "blocked")).isEqualTo("BLOCKED\nLATE_BLOCKED\n");
				assertThat(calls).hasValue(0);
			}
			List<String> valid = List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=1");
			assertThat(runProbe(server.uri(), valid, "mutated")).isEqualTo("CURRENT_BLOCKED\n");
			assertThat(calls).hasValue(0);
			assertThat(runProbe(server.uri(), valid, "success")).isEqualTo("SUCCESS\n");
			assertThat(calls).hasValue(1);
		}
	}

	@Test
	void rejectsDiagnosticLoggingBeforeSendingEvenWhenStartupSettingWasCleared(CapturedOutput output) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			calls.incrementAndGet();
			reply(exchange, 200, "[]");
		})) {
			List<String> valid = List.of("-D" + DISABLE + "=true", "-D" + RETRIES + "=1");
			List<String> logging = new ArrayList<>(valid);
			logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
			assertThat(runProbe(server.uri(), logging, "logging")).isEqualTo("LOGGING_BLOCKED\n");
			assertThat(calls).hasValue(0);
			assertThat(runProbe(server.uri(), logging, "clearedLogging")).isEqualTo("LOGGING_BLOCKED\n");
			assertThat(calls).hasValue(0);
			logging.add("-D" + HTTP_LOG + "=");
			assertThat(runProbe(server.uri(), logging, "logging")).isEqualTo("LOGGING_BLOCKED\n");
			assertThat(calls).hasValue(0);
			assertThat(runProbe(server.uri(), valid, "lateLogging")).isEqualTo("LOGGING_BLOCKED\n");
			assertThat(calls).hasValue(0);
			List<String> disabledLogging = new ArrayList<>(valid);
			disabledLogging.add("-D" + HTTP_LOG + "=");
			assertThat(runProbe(server.uri(), disabledLogging, "success")).isEqualTo("SUCCESS\n");
			assertThat(calls).hasValue(1);
		}
		assertSafeOutput(output, List.of());
	}

	private static String runProbe(URI endpoint, List<String> options, String mode) throws Exception {
		// Existing build configuration exports the entire test runtime classpath.
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).isNotBlank();
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Xint"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, StartupProbe.class.getName(), endpoint.toString(), mode));
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

	/** Isolated JVM, fake environment only; never contacts the production endpoint. */
	public static final class StartupProbe {
		public static void main(String[] args) {
			try {
				URI endpoint = URI.create(args[0]);
				if (args[1].equals("success")) {
					try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						if (!client.getAccountTrades(ALIAS, request()).trades().isEmpty()) { throw new AssertionError(); }
						System.out.println("SUCCESS");
					}
				}
				else if (args[1].equals("mutated")) {
					try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						System.setProperty(RETRIES, "2");
						assertBlocked(() -> client.getAccountTrades(ALIAS, request()));
						System.out.println("CURRENT_BLOCKED");
					}
				}
				else if (args[1].equals("logging") || args[1].equals("clearedLogging")) {
					if (args[1].equals("clearedLogging")) { System.clearProperty(HTTP_LOG); }
					assertBlocked(() -> { try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						client.getAccountTrades(ALIAS, request());
					} });
					System.out.println("LOGGING_BLOCKED");
				}
				else if (args[1].equals("lateLogging")) {
					try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						System.setProperty(HTTP_LOG, "requests,headers,content,errors");
						assertBlocked(() -> client.getAccountTrades(ALIAS, request()));
						System.out.println("LOGGING_BLOCKED");
					}
				}
				else {
					assertBlocked(() -> { try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						client.getAccountTrades(ALIAS, request());
					} });
					System.out.println("BLOCKED");
					System.setProperty(DISABLE, "true");
					System.setProperty(RETRIES, "1");
					assertBlocked(() -> { try (var client = new BinanceAccountTradesClient(endpoint, () -> KEYS, CLOCK)) {
						client.getAccountTrades(ALIAS, request());
					} });
					System.out.println("LATE_BLOCKED");
				}
			}
			catch (Throwable failure) {
				System.err.println("PROBE_FAILED:" + failure.getClass().getSimpleName());
				System.exit(1);
			}
		}

		private static void assertBlocked(Runnable action) {
			try { action.run(); }
			catch (BinanceApiException exception) {
				if (exception.code() == BinanceErrorCode.UNSAFE_TRANSPORT_CONFIGURATION) { return; }
			}
			throw new AssertionError("Unsafe transport accepted");
		}
	}

	private static String hmac(String query) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.US_ASCII)));
	}

	private static BinanceAccountTradesRequest request() {
		return new BinanceAccountTradesRequest("BTCUSDT");
	}

	private static ServerSocket loopbackSocket() throws IOException {
		return new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
	}

	private static URI uri(ServerSocket socket) {
		return URI.create("http://127.0.0.1:" + socket.getLocalPort() + PATH);
	}

	private static BufferedReader reader(java.net.Socket socket) throws IOException {
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
	}

	private static boolean readRequest(BufferedReader input, AtomicInteger calls) throws IOException {
		String line = input.readLine();
		if (line == null) { return false; }
		assertThat(line).startsWith("GET " + PATH + "?symbol=BTCUSDT&limit=500&recvWindow=5000&timestamp=1700000000123&signature=")
				.endsWith(" HTTP/1.1");
		calls.incrementAndGet();
		while ((line = input.readLine()) != null && !line.isEmpty()) { }
		return true;
	}

	private static void expect(BinanceErrorCode code, Runnable action) {
		expect(code, action, List.of());
	}

	private static void expect(BinanceErrorCode code, Runnable action, List<String> signatures) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BinanceApiException.class, exception -> {
			assertThat(exception.code()).isEqualTo(code);
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			StringWriter trace = new StringWriter();
			exception.printStackTrace(new PrintWriter(trace));
			String exposed = exception.getMessage() + exception + trace;
			assertThat(exposed).doesNotContain(KEY, SECRET, ALIAS, PRIVATE, "signature=", "http://", "https://");
			for (String signature : signatures) { assertThat(exposed).doesNotContain(signature); }
		});
	}

	private static void assertSafeOutput(CapturedOutput output, List<String> signatures) {
		assertThat(output.getAll()).doesNotContain(KEY, SECRET, ALIAS, PRIVATE);
		for (String signature : signatures) { assertThat(output.getAll()).doesNotContain(signature); }
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
		try (var stream = exchange.getResponseBody()) {
			if (status != 204) { stream.write(bytes); }
		}
	}

	private static final class LocalServer implements AutoCloseable {
		private final HttpServer server;
		private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		private final List<BinanceAccountTradesClient> clients = new ArrayList<>();

		LocalServer(HttpHandler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext(PATH, handler);
			server.setExecutor(executor);
			server.start();
		}

		URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH); }
		BinanceAccountTradesClient client() { return client(() -> KEYS, CLOCK); }
		BinanceAccountTradesClient client(Supplier<Map<String, String>> environment, Clock clock) {
			var client = new BinanceAccountTradesClient(uri(), environment, clock);
			clients.add(client);
			return client;
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
			clients.forEach(BinanceAccountTradesClient::close);
		}
	}
}
