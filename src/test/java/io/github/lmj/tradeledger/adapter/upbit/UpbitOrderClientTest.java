package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.nio.file.Path;
import java.time.Duration;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UpbitOrderClientTest {

	private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
	private static final String UPPER_ID = "123E4567-E89B-12D3-A456-426614174000";
	private static final String BODY_MARKER = "private-response-marker";
	private static final String DISABLE_RETRY = "jdk.httpclient.disableRetryConnect";
	private static final String RETRY_LIMIT = "jdk.httpclient.redirects.retrylimit";
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
	void verifiesStartupOptionsAcrossIndependentJvms() throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			reply(exchange, 200, ORDER);
		})) {
			assertThat(runStartupProbe(server.uri(), false)).containsExactly(
					"MISSING:UNSAFE_TRANSPORT_CONFIGURATION", "LATE:UNSAFE_TRANSPORT_CONFIGURATION");
			assertThat(requests).isEmpty();
			assertThat(runStartupProbe(server.uri(), true)).containsExactly("STARTUP:ORDER:" + ID);
			assertThat(requests).containsExactly("GET /v1/order?uuid=" + ID);
		}
	}

	private static List<String> runStartupProbe(URI endpoint, boolean startupOptions) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).as("Gradle test runtime classpath for the child JVM").isNotBlank();
		// Avoid compiler and parallel GC threads in the extra JVM on constrained test hosts.
		List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Xint"));
		if (startupOptions) {
			command.add("-D" + DISABLE_RETRY + "=true");
			command.add("-D" + RETRY_LIMIT + "=1");
		}
		command.addAll(List.of("-cp", classpath, StartupOptionsProbe.class.getName(),
				endpoint.toString(), startupOptions ? "startup" : "missing"));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
			builder.environment().remove(variable);
		}
		Process process = builder.start();
		try (var input = process.getInputStream()) {
			process.getOutputStream().close();
			int outputLimit = 8192;
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			boolean exited = false;
			// Drain without a reader thread; both output storage and process lifetime are bounded.
			while (true) {
				int available;
				while ((available = input.available()) > 0) {
					int count = input.read(buffer, 0, Math.min(available, buffer.length));
					if (count < 0) {
						break;
					}
					output.write(buffer, 0, count);
					assertThat(output.size()).as("Child JVM output bytes").isLessThanOrEqualTo(outputLimit);
				}
				// Drain once more after observing exit so the final result marker cannot race this check.
				if (exited) {
					break;
				}
				assertThat(System.nanoTime() < deadline).as("Child JVM must finish within 20 seconds").isTrue();
				exited = process.waitFor(25, TimeUnit.MILLISECONDS);
			}
			String result = output.toString(StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("Child JVM exit status; bounded output: %s", result).isZero();
			return result.lines().toList();
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Child JVM cleanup").isTrue();
			}
		}
	}

	/** Separate JVM entry point; never reads credentials from the process environment. */
	public static final class StartupOptionsProbe {
		public static void main(String[] arguments) {
			try {
				URI endpoint = URI.create(arguments[0]);
				if ("startup".equals(arguments[1])) {
					String orderId = new UpbitOrderClient(endpoint, () -> KEYS).getOrder(ID).orderId();
					if (!ID.equals(orderId)) {
						throw new AssertionError("Unexpected fake order");
					}
					System.out.println("STARTUP:ORDER:" + orderId);
				}
				else if ("missing".equals(arguments[1])) {
					if (System.getProperty(DISABLE_RETRY) != null || System.getProperty(RETRY_LIMIT) != null) {
						throw new AssertionError("Retry options must initially be absent");
					}
					requireBlocked(endpoint, "MISSING");
					System.setProperty(DISABLE_RETRY, "true");
					System.setProperty(RETRY_LIMIT, "1");
					requireBlocked(endpoint, "LATE");
				}
				else {
					throw new AssertionError("Unknown probe mode");
				}
			}
			catch (Throwable failure) {
				// Report no environment, authentication values, response bodies or exception messages.
				System.err.println("PROBE_FAILED:" + failure.getClass().getSimpleName());
				System.exit(1);
			}
		}

		private static void requireBlocked(URI endpoint, String phase) {
			try {
				new UpbitOrderClient(endpoint, () -> KEYS).getOrder(ID);
			}
			catch (UpbitApiException exception) {
				if (exception.code() != UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION) {
					throw new AssertionError("Unexpected Upbit error category");
				}
				System.out.println(phase + ":UNSAFE_TRANSPORT_CONFIGURATION");
				return;
			}
			throw new AssertionError("Unsafe transport was accepted");
		}
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

	@ParameterizedTest
	@ValueSource(strings = {"123e4567-e89b-12d3-a456-426614174001", "1-1-1-1-1",
			"123e4567-e89b-12d3-a456-42661417400", "123e4567e89b12d3a456426614174000",
			"123e4567-e89b-12d3-g456-426614174000", " " + ID, ID + " ",
			"123e4567-e89b-12d3-a456-426614174 00"})
	void rejectsMismatchedOrNoncanonicalResponseIdsWithoutLeakingData(String responseId) throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			String body = ORDER.replace(ID, responseId).replace("\"market\":",
					"\"private\":\"" + BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET
							+ token + "\",\"market\":");
			reply(exchange, 200, body);
		})) {
			UpbitOrderClient client = server.client();
			expect(UpbitErrorCode.INVALID_RESPONSE, () -> client.getOrder(ID), tokens, responseId);
			assertThat(requests).containsExactly("GET /v1/order?uuid=" + ID);
		}
	}

	@ParameterizedTest
	@CsvSource({ID + "," + ID, ID + "," + UPPER_ID, UPPER_ID + "," + ID, UPPER_ID + "," + UPPER_ID})
	void acceptsMatchingResponseIdsAndPreservesAmountsAndFills(String requestId, String responseId) throws Exception {
		String body = """
				{"uuid":"%s","market":"KRW-BTC","side":"bid","ord_type":"limit","state":"done",
				"created_at":"2026-07-01T01:00:00Z","executed_volume":"0.0000000000000000000200",
				"paid_fee":"0.0000000000000000000100","trades":[
				{"uuid":"fill-1","market":"KRW-BTC","side":"bid","price":"123.456789012345678901234567890",
				"volume":"0.0000000000000000000100","funds":"0.0000000000000000012345678900",
				"created_at":"2026-07-01T01:00:00Z"},
				{"uuid":"fill-2","market":"KRW-BTC","side":"bid","price":"234.567890123456789012345678900",
				"volume":"0.0000000000000000000100","funds":"0.0000000000000000023456789000",
				"created_at":"2026-07-01T01:00:00Z"}]}
				""".formatted(responseId);
		List<String> requests = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			reply(exchange, 200, body);
		})) {
			var snapshot = server.client().getOrder(requestId);
			assertThat(snapshot.orderId()).isEqualTo(responseId);
			assertThat(snapshot.executedVolume()).isEqualTo("0.0000000000000000000200");
			assertThat(snapshot.paidFee()).isEqualTo("0.0000000000000000000100");
			assertThat(snapshot.feeCurrency()).isEmpty();
			assertThat(snapshot.trades()).hasSize(2);
			var first = snapshot.trades().getFirst();
			assertThat(first.tradeId()).isEqualTo("fill-1");
			assertThat(first.market()).isEqualTo("KRW-BTC");
			assertThat(first.side()).isEqualTo("bid");
			assertThat(first.price()).isEqualTo("123.456789012345678901234567890");
			assertThat(first.volume()).isEqualTo("0.0000000000000000000100");
			assertThat(first.funds()).isEqualTo("0.0000000000000000012345678900");
			assertThat(first.createdAt()).isEqualTo(snapshot.createdAt());
			var second = snapshot.trades().get(1);
			assertThat(second.tradeId()).isEqualTo("fill-2");
			assertThat(second.price()).isEqualTo("234.567890123456789012345678900");
			assertThat(second.volume()).isEqualTo("0.0000000000000000000100");
			assertThat(second.funds()).isEqualTo("0.0000000000000000023456789000");
			assertThat(requests).containsExactly("GET /v1/order?uuid=" + requestId);
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

	private static void expect(UpbitErrorCode code, Runnable action, List<String> tokens, String... responseIds) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(UpbitApiException.class, exception -> {
			assertThat(exception.code()).isEqualTo(code);
			assertThat(exception.getCause()).isNull();
			assertThat(exception.getSuppressed()).isEmpty();
			StringWriter trace = new StringWriter();
			exception.printStackTrace(new PrintWriter(trace));
			assertThat(exception.getMessage() + exception + trace)
					.doesNotContain(UpbitJwtSignerTest.ACCESS, UpbitJwtSignerTest.SECRET, BODY_MARKER);
			if (responseIds.length > 0) {
				assertThat(exception.getMessage() + exception + trace).doesNotContain(responseIds);
			}
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
