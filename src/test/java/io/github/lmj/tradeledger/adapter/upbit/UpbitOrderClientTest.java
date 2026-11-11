package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class UpbitOrderClientTest {

	private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
	private static final String BODY_MARKER = "private-response-marker";
	private static final Map<String, String> KEYS = Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS,
			"UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET);
	private static final String ORDER = """
			{"uuid":"123e4567-e89b-12d3-a456-426614174000","market":"KRW-BTC","side":"bid",
			"ord_type":"limit","state":"wait","created_at":"2026-07-01T01:00:00Z",
			"executed_volume":"0","paid_fee":"0","trades":[]}
			""";

	@Test
	void requiresStartupRetryLimitsEvenIfPropertiesWereSetLater() {
		String disable = "-Djdk.httpclient.disableRetryConnect=true";
		String limit = "-Djdk.httpclient.redirects.retrylimit=1";
		for (List<String> arguments : List.of(List.<String>of(), List.of(disable), List.of(limit),
				List.of(disable, "-Djdk.httpclient.redirects.retrylimit=5"),
				List.of("-Djdk.httpclient.disableRetryConnect=false", disable, limit))) {
			expect(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION,
					() -> UpbitOrderClient.requireNoRetryConfiguration(arguments, "true", "1"));
		}
		expect(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION,
				() -> UpbitOrderClient.requireNoRetryConfiguration(List.of(disable, limit), "false", "1"));
		expect(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION,
				() -> UpbitOrderClient.requireNoRetryConfiguration(List.of(disable, limit), "true", "5"));
		UpbitOrderClient.requireNoRetryConfiguration(List.of(disable, limit), "true", "1");
	}

	@Test
	void neverResendsGetWhenServerClosesBeforeResponseHeaders() throws Exception {
		assertNoResendAfterDisconnect(false);
		assertNoResendAfterDisconnect(true);
	}

	private static void assertNoResendAfterDisconnect(boolean reuseConnection) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		try (ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
				var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var receiver = executor.submit(() -> {
				while (!server.isClosed()) {
					try (var socket = server.accept()) {
						socket.setSoTimeout(3000);
						var input = new BufferedReader(new InputStreamReader(socket.getInputStream(),
								StandardCharsets.US_ASCII));
						String line;
						while ((line = input.readLine()) != null) {
							if (!line.equals("GET /v1/order?uuid=" + ID + " HTTP/1.1")) {
								throw new IllegalStateException("Unexpected local request");
							}
							int count = requests.incrementAndGet();
							while ((line = input.readLine()) != null && !line.isEmpty()) {
								// Consume request headers before responding or dropping the connection.
							}
							if (!reuseConnection || count != 1) {
								break;
							}
							byte[] body = ORDER.getBytes(StandardCharsets.UTF_8);
							var output = socket.getOutputStream();
							output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
									+ "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
							output.write(body);
							output.flush();
						}
					}
					catch (IOException exception) {
						if (!server.isClosed()) {
							throw new java.io.UncheckedIOException(exception);
						}
					}
				}
			});
			try {
				UpbitOrderClient client = new UpbitOrderClient(
						URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1/order"), () -> KEYS);
				if (reuseConnection) {
					assertThat(client.getOrder(ID).orderId()).isEqualTo(ID);
				}
				expect(UpbitErrorCode.NETWORK_ERROR, () -> client.getOrder(ID));
			}
			finally {
				server.close();
			}
			receiver.get(3, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(requests).hasValue(reuseConnection ? 2 : 1);
		}
	}

	@Test
	void sendsExactlyOneGetPerCallWithMatchingQueryHashAndFreshNonce() throws Exception {
		List<String> methods = new CopyOnWriteArrayList<>();
		List<URI> uris = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			methods.add(exchange.getRequestMethod());
			uris.add(exchange.getRequestURI());
			tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
			reply(exchange, 200, ORDER);
		})) {
			UpbitOrderClient client = server.client();
			assertThat(client.getOrder(ID).orderId()).isEqualTo(ID);
			assertThat(client.getOrder(ID).trades()).isEmpty();
			assertThat(methods).containsExactly("GET", "GET");
			assertThat(uris).hasSize(2);
			List<String> nonces = new java.util.ArrayList<>();
			for (int i = 0; i < 2; i++) {
				assertThat(uris.get(i).getPath()).isEqualTo("/v1/order");
				assertThat(uris.get(i).getRawQuery()).isEqualTo("uuid=" + ID);
				assertThat(tokens.get(i)).startsWith("Bearer ");
				nonces.add(UpbitJwtSignerTest.verify(tokens.get(i).substring(7),
						URLDecoder.decode(uris.get(i).getRawQuery(), StandardCharsets.UTF_8))
						.get("nonce").asString());
			}
			assertThat(nonces.get(0)).isNotEqualTo(nonces.get(1));
		}
	}

	@Test
	void rejectsNoncanonicalIdsAndMissingKeysBeforeAnyHttpRequest() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, ORDER);
		})) {
			UpbitOrderClient client = server.client();
			for (String invalid : List.of("", " ", "1-1-1-1-1", ID + " ", " " + ID,
					ID + "&other=1", ID.replace("-", ""), ID.substring(1), ID.replace('a', 'g'))) {
				expect(UpbitErrorCode.INVALID_UUID, () -> client.getOrder(invalid));
			}
			expect(UpbitErrorCode.INVALID_UUID, () -> client.getOrder(null));
			List<Map<String, String>> missingKeys = List.of(Map.of(),
					Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS),
					Map.of("UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET),
					Map.of("UPBIT_ACCESS_KEY", " ", "UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET),
					Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS, "UPBIT_SECRET_KEY", ""));
			for (Map<String, String> keys : missingKeys) {
				expect(UpbitErrorCode.MISSING_CREDENTIALS,
						() -> new UpbitOrderClient(server.uri(), () -> keys).getOrder(ID));
			}
			assertThat(requests).hasValue(0);
		}
	}

	@Test
	void classifiesHttpFailuresAndNeverRetainsResponseCredentialsOrToken() throws Exception {
		Map<Integer, UpbitErrorCode> cases = Map.ofEntries(
				Map.entry(400, UpbitErrorCode.BAD_REQUEST), Map.entry(401, UpbitErrorCode.AUTHENTICATION_FAILED),
				Map.entry(403, UpbitErrorCode.PERMISSION_DENIED), Map.entry(404, UpbitErrorCode.ORDER_NOT_FOUND),
				Map.entry(418, UpbitErrorCode.IP_BLOCKED), Map.entry(429, UpbitErrorCode.RATE_LIMITED),
				Map.entry(500, UpbitErrorCode.SERVER_ERROR), Map.entry(503, UpbitErrorCode.SERVER_ERROR),
				Map.entry(599, UpbitErrorCode.SERVER_ERROR), Map.entry(302, UpbitErrorCode.REDIRECT_REJECTED),
				Map.entry(204, UpbitErrorCode.UNEXPECTED_STATUS));
		AtomicInteger status = new AtomicInteger();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			reply(exchange, status.get(), BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token);
		})) {
			UpbitOrderClient client = server.client();
			for (var entry : cases.entrySet()) {
				status.set(entry.getKey());
				expect(entry.getValue(), () -> client.getOrder(ID), tokens);
			}
			assertThat(requests).hasValue(cases.size());
		}
	}

	@Test
	void recognizesPermissionErrorsInBoth401And403() throws Exception {
		AtomicInteger status = new AtomicInteger(401);
		try (LocalServer server = new LocalServer(exchange -> reply(exchange, status.get(),
				"{\"error\":{\"name\":\"out_of_scope\",\"message\":\"" + BODY_MARKER + "\"}}"))) {
			UpbitOrderClient client = server.client();
			expect(UpbitErrorCode.PERMISSION_DENIED, () -> client.getOrder(ID));
			status.set(403);
			expect(UpbitErrorCode.PERMISSION_DENIED, () -> client.getOrder(ID));
		}
	}

	@Test
	void neverFollowsRedirectOrForwardsAuthorizationToAnotherServer() throws Exception {
		AtomicInteger targetRequests = new AtomicInteger();
		AtomicInteger sourceRequests = new AtomicInteger();
		try (LocalServer target = new LocalServer(exchange -> {
			targetRequests.incrementAndGet();
			reply(exchange, 200, ORDER);
		}); LocalServer source = new LocalServer(exchange -> {
			sourceRequests.incrementAndGet();
			exchange.getResponseHeaders().set("Location", target.uri().toString());
			reply(exchange, 307, BODY_MARKER);
		})) {
			expect(UpbitErrorCode.REDIRECT_REJECTED, () -> source.client().getOrder(ID));
			assertThat(sourceRequests).hasValue(1);
			assertThat(targetRequests).hasValue(0);
		}
	}

	@Test
	void boundsChunkedBodyWithoutTrustingContentLengthAndAcceptsExactlyOneMiB() throws Exception {
		AtomicInteger size = new AtomicInteger(UpbitOrderClient.MAX_RESPONSE_BYTES);
		try (LocalServer server = new LocalServer(exchange -> {
			exchange.sendResponseHeaders(200, 0); // chunked, no Content-Length
			try (var output = exchange.getResponseBody()) {
				output.write(ORDER.getBytes(StandardCharsets.UTF_8));
				int padding = size.get() - ORDER.getBytes(StandardCharsets.UTF_8).length;
				byte[] spaces = " ".repeat(4096).getBytes(StandardCharsets.UTF_8);
				while (padding > 0) {
					int chunk = Math.min(spaces.length, padding);
					output.write(spaces, 0, chunk);
					padding -= chunk;
				}
			}
		})) {
			UpbitOrderClient client = server.client();
			assertThat(client.getOrder(ID).orderId()).isEqualTo(ID);
			size.incrementAndGet();
			expect(UpbitErrorCode.RESPONSE_TOO_LARGE, () -> client.getOrder(ID));
		}
	}

	@Test
	void cancelsSubscriptionAsSoonAsStreamingLimitIsExceeded() {
		var subscriber = new UpbitOrderClient.BoundedBodySubscriber();
		AtomicBoolean cancelled = new AtomicBoolean();
		subscriber.onSubscribe(new Flow.Subscription() {
			@Override
			public void request(long count) {
			}
			@Override
			public void cancel() {
				cancelled.set(true);
			}
		});
		subscriber.onNext(List.of(ByteBuffer.allocate(UpbitOrderClient.MAX_RESPONSE_BYTES)));
		assertThat(cancelled).isFalse();
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
		assertThat(cancelled).isTrue();
		assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
				.hasCauseInstanceOf(UpbitApiException.class);
	}

	@Test
	void appliesTenSecondDeadlineToBodyEvenAfterHeadersArrive() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, 0);
			try (var output = exchange.getResponseBody()) {
				output.write('{');
				output.flush();
				try {
					release.await();
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		})) {
			assertThat(UpbitOrderClient.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
			assertThat(UpbitOrderClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
			UpbitOrderClient client = server.client();
			long started = System.nanoTime();
			try {
				expect(UpbitErrorCode.TIMEOUT, () -> client.getOrder(ID));
				assertThat(Duration.ofNanos(System.nanoTime() - started))
						.isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
				assertThat(requests).hasValue(1);
			}
			finally {
				release.countDown();
			}
		}
	}

	@Test
	void sanitizesMalformedSuccessNetworkFailureAndEnvironmentFailure() throws Exception {
		try (LocalServer server = new LocalServer(exchange -> reply(exchange, 200, BODY_MARKER))) {
			expect(UpbitErrorCode.INVALID_RESPONSE, () -> server.client().getOrder(ID));
			expect(UpbitErrorCode.MISSING_CREDENTIALS, () -> new UpbitOrderClient(server.uri(), () -> {
				throw new IllegalStateException(BODY_MARKER + UpbitJwtSignerTest.SECRET);
			}).getOrder(ID));
		}
		int unusedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			unusedPort = socket.getLocalPort();
		}
		UpbitOrderClient disconnected = new UpbitOrderClient(
				URI.create("http://127.0.0.1:" + unusedPort + "/v1/order"), () -> KEYS);
		expect(UpbitErrorCode.NETWORK_ERROR, () -> disconnected.getOrder(ID));
	}

	@Test
	void testSeamRefusesRemoteHostsAndOtherPaths() {
		for (String endpoint : List.of("https://api.upbit.com/v1/order", "http://example.com/v1/order",
				"http://127.0.0.1/v1/orders", "http://127.0.0.1/v1/order?uuid=x",
				"http://127.0.0.1/v1/order#x", "http://user@127.0.0.1/v1/order")) {
			assertThatThrownBy(() -> new UpbitOrderClient(URI.create(endpoint), () -> KEYS))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static void expect(UpbitErrorCode code, Runnable action) {
		expect(code, action, List.of());
	}

	private static void expect(UpbitErrorCode code, Runnable action, List<String> tokens) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(UpbitApiException.class, exception -> {
			assertThat(exception.code()).isEqualTo(code);
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			StringWriter trace = new StringWriter();
			exception.printStackTrace(new PrintWriter(trace));
			assertThat(exception.getMessage() + exception + trace)
					.doesNotContain(UpbitJwtSignerTest.ACCESS, UpbitJwtSignerTest.SECRET, BODY_MARKER);
			for (String token : tokens) {
				assertThat(trace.toString()).doesNotContain(token, token.substring(7));
			}
		});
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
		try (var output = exchange.getResponseBody()) {
			if (status != 204) {
				output.write(bytes);
			}
		}
	}

	private static final class LocalServer implements AutoCloseable {
		private final HttpServer server;
		private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

		LocalServer(HttpHandler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", handler);
			server.setExecutor(executor);
			server.start();
		}

		URI uri() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/order");
		}

		UpbitOrderClient client() {
			return new UpbitOrderClient(uri(), () -> KEYS);
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
		}
	}
}
