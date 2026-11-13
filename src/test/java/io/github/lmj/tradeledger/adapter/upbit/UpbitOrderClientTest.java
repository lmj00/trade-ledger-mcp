package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class UpbitOrderClientTest {

	private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
	private static final String UPPER_ID = "123E4567-E89B-12D3-A456-426614174000";
	private static final String BODY_MARKER = "private-response-marker";
	private static final String DISABLE_RETRY = "jdk.httpclient.disableRetryConnect";
	private static final String RETRY_LIMIT = "jdk.httpclient.redirects.retrylimit";
	private static final String HTTP_LOG = "jdk.httpclient.HttpClient.log";
	private static final Map<String, String> KEYS = Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS,
			"UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET);
	private static final String ORDER = """
			{"uuid":"123e4567-e89b-12d3-a456-426614174000","market":"KRW-BTC","side":"bid",
			"ord_type":"limit","state":"wait","created_at":"2026-07-01T01:00:00Z",
			"executed_volume":"0","paid_fee":"0","trades":[]}
			""";
	private static final String CLOSED_QUERY = "states%5B%5D=done&states%5B%5D=cancel&limit=100&order_by=desc";
	private static final String CLOSED_SIGNING_QUERY = "states[]=done&states[]=cancel&limit=100&order_by=desc";
	private static final String CLOSED_ORDER = """
			{"uuid":"123e4567-e89b-12d3-a456-426614174000","market":"KRW-BTC","side":"bid",
			"ord_type":"limit","state":"done","created_at":"2026-07-01T10:00:00+09:00",
			"executed_volume":"0.0000000000000000000200","paid_fee":"0.0000000000000000000100",
			"trades_count":18446744073709551616}
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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void verifiesStartupOptionsAcrossIndependentJvms(boolean closed) throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			reply(exchange, 200, successBody(closed));
		})) {
			assertThat(runStartupProbe(server.uri(closed), false)).containsExactly(
					"MISSING:UNSAFE_TRANSPORT_CONFIGURATION", "LATE:UNSAFE_TRANSPORT_CONFIGURATION");
			assertThat(requests).isEmpty();
			assertThat(runStartupProbe(server.uri(closed), true)).containsExactly(
					"STARTUP:" + (closed ? "CLOSED:" : "ORDER:") + ID);
			assertThat(requests).containsExactly("GET " + requestTarget(closed));
		}
	}

	private static List<String> runStartupProbe(URI endpoint, boolean startupOptions) throws Exception {
		return runStartupProbe(endpoint, startupOptions
				? List.of("-D" + DISABLE_RETRY + "=true", "-D" + RETRY_LIMIT + "=1") : List.of(),
				startupOptions ? "startup" : "missing");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rejectsDiagnosticLoggingAcrossIndependentJvmsBeforeCredentialsAndHttp(boolean closed) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, successBody(closed).replace("\"uuid\":",
					"\"private\":\"" + BODY_MARKER + "\",\"uuid\":"));
		})) {
			List<String> valid = List.of("-D" + DISABLE_RETRY + "=true", "-D" + RETRY_LIMIT + "=1");
			List<String> logging = new ArrayList<>(valid);
			logging.add("-D" + HTTP_LOG + "=requests,headers,content,errors");
			for (String mode : List.of("logging", "clearedLogging")) {
				assertThat(runStartupProbe(server.uri(closed), logging, mode))
						.containsExactly("LOGGING:UNSAFE_TRANSPORT_CONFIGURATION");
				assertThat(requests).hasValue(0);
			}
			logging.add("-D" + HTTP_LOG + "=");
			assertThat(runStartupProbe(server.uri(closed), logging, "logging"))
					.containsExactly("LOGGING:UNSAFE_TRANSPORT_CONFIGURATION");
			assertThat(requests).hasValue(0);
			assertThat(runStartupProbe(server.uri(closed), valid, "lateLogging"))
					.containsExactly("LOGGING:UNSAFE_TRANSPORT_CONFIGURATION");
			assertThat(requests).hasValue(0);
			// A deterministic change in the fake supplier exercises the final send guard.
			assertThat(runStartupProbe(server.uri(closed), valid, "signingLogging"))
					.containsExactly("LOGGING:UNSAFE_TRANSPORT_CONFIGURATION");
			assertThat(requests).hasValue(0);
			List<String> empty = new ArrayList<>(valid);
			empty.add("-D" + HTTP_LOG + "=");
			assertThat(runStartupProbe(server.uri(closed), empty, "startup"))
					.containsExactly("STARTUP:" + (closed ? "CLOSED:" : "ORDER:") + ID);
			assertThat(requests).hasValue(1);
		}
	}

	private static List<String> runStartupProbe(URI endpoint, List<String> options, String mode) throws Exception {
		String classpath = System.getProperty("upbit.test.runtimeClasspath");
		assertThat(classpath).as("Gradle test runtime classpath for the child JVM").isNotBlank();
		// Avoid compiler and parallel GC threads in the extra JVM on constrained test hosts.
		List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xms16m", "-Xmx96m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Xint"));
		command.addAll(options);
		command.addAll(List.of("-cp", classpath, StartupOptionsProbe.class.getName(),
				endpoint.toString(), mode));
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
			assertThat(result).doesNotContain(UpbitJwtSignerTest.ACCESS, UpbitJwtSignerTest.SECRET,
					BODY_MARKER, "Bearer ", "eyJ");
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
					AtomicInteger credentials = new AtomicInteger();
					try (UpbitOrderClient client = new UpbitOrderClient(endpoint, () -> {
						credentials.incrementAndGet();
						return KEYS;
					})) {
						boolean closed = endpoint.getPath().equals("/v1/orders/closed");
						String orderId = closed ? client.getClosedOrders(new UpbitClosedOrdersRequest())
								.orders().getFirst().orderId() : client.getOrder(ID).orderId();
						if (!ID.equals(orderId)) {
							throw new AssertionError("Unexpected fake order");
						}
						assertThat(credentials).hasValue(1);
						System.out.println("STARTUP:" + (closed ? "CLOSED:" : "ORDER:") + orderId);
					}
				}
				else if (List.of("logging", "clearedLogging", "lateLogging", "signingLogging")
						.contains(arguments[1])) {
					String mode = arguments[1];
					AtomicInteger credentials = new AtomicInteger();
					Supplier<Map<String, String>> keys = () -> {
						credentials.incrementAndGet();
						if (mode.equals("signingLogging")) {
							System.setProperty(HTTP_LOG, "requests,headers,content,errors");
						}
						return KEYS;
					};
					if (mode.equals("lateLogging") || mode.equals("signingLogging")) {
						try (UpbitOrderClient client = new UpbitOrderClient(endpoint, keys)) {
							if (mode.equals("lateLogging")) {
								System.setProperty(HTTP_LOG, "requests,headers,content,errors");
							}
							// Invalid inputs retain precedence over transport configuration.
							expect(UpbitErrorCode.INVALID_UUID, () -> client.getOrder(BODY_MARKER));
							expect(UpbitErrorCode.BAD_REQUEST, () -> client.getClosedOrders(null));
							expect(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION,
									() -> get(client, endpoint.getPath().equals("/v1/orders/closed")));
						}
					}
					else {
						if (mode.equals("clearedLogging")) {
							System.clearProperty(HTTP_LOG);
						}
						expect(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION, () -> {
							try (UpbitOrderClient ignored = new UpbitOrderClient(endpoint, keys)) {
								throw new AssertionError("Unsafe construction was accepted");
							}
						});
					}
					assertThat(credentials).hasValue(mode.equals("signingLogging") ? 1 : 0);
					System.out.println("LOGGING:UNSAFE_TRANSPORT_CONFIGURATION");
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
			try (UpbitOrderClient client = new UpbitOrderClient(endpoint, () -> KEYS)) {
				if (endpoint.getPath().equals("/v1/orders/closed")) {
					client.getClosedOrders(new UpbitClosedOrdersRequest());
				}
				else {
					client.getOrder(ID);
				}
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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void neverResendsGetWhenServerClosesBeforeResponseHeaders(boolean closed) throws Exception {
		assertNoResendAfterDisconnect(false, closed);
		assertNoResendAfterDisconnect(true, closed);
	}

	private static void assertNoResendAfterDisconnect(boolean reuseConnection, boolean closed) throws Exception {
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
							if (!line.equals("GET " + requestTarget(closed) + " HTTP/1.1")) {
								throw new IllegalStateException("Unexpected local request");
							}
							int count = requests.incrementAndGet();
							while ((line = input.readLine()) != null && !line.isEmpty()) {
								// Consume request headers before responding or dropping the connection.
							}
							if (!reuseConnection || count != 1) {
								break;
							}
							byte[] body = successBody(closed).getBytes(StandardCharsets.UTF_8);
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
			try (UpbitOrderClient client = new UpbitOrderClient(
					URI.create("http://127.0.0.1:" + server.getLocalPort()
							+ (closed ? "/v1/orders/closed" : "/v1/order")), () -> KEYS)) {
				if (reuseConnection) {
					assertSuccessfulGet(client, closed);
				}
				expect(UpbitErrorCode.NETWORK_ERROR, () -> get(client, closed));
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
	void sendsDefaultClosedOrdersOncePerCallWithIndependentSignatureAndFreshNonce() throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
			reply(exchange, 200, "[]");
		})) {
			// Either accepted local path supplies only the origin; the method fixes its own path.
			UpbitOrderClient client = server.client();
			var first = client.getClosedOrders(new UpbitClosedOrdersRequest());
			assertThat(first.serviceId()).isEqualTo("upbit");
			assertThat(first.orders()).isEmpty();
			assertThat(first.possiblyTruncated()).isFalse();
			assertThat(requests).containsExactly("GET /v1/orders/closed?" + CLOSED_QUERY);
			assertThat(client.getClosedOrders(new UpbitClosedOrdersRequest()).orders()).isEmpty();
			assertThat(requests).containsExactly("GET /v1/orders/closed?" + CLOSED_QUERY,
					"GET /v1/orders/closed?" + CLOSED_QUERY);
			assertThat(tokens).hasSize(2);
			List<String> nonces = new ArrayList<>();
			for (String token : tokens) {
				assertThat(token).startsWith("Bearer ");
				nonces.add(UpbitJwtSignerTest.verify(token.substring(7), CLOSED_SIGNING_QUERY)
						.get("nonce").asString());
			}
			assertThat(nonces.get(0)).isNotEqualTo(nonces.get(1));
		}
	}

	@Test
	void serializesExclusiveStatesOptionsAndNormalizedTimesWithIndependentQueryHashes() throws Exception {
		record QueryCase(UpbitClosedOrdersRequest request, String signing, String encoded) { }
		List<QueryCase> cases = List.of(
				new QueryCase(new UpbitClosedOrdersRequest("KRW-BTC", "done", null,
						"2026-07-01T10:00:00+09:00", "2026-07-08T01:00:00Z", 1, "asc"),
						"market=KRW-BTC&state=done&start_time=2026-07-01T01:00:00Z"
								+ "&end_time=2026-07-08T01:00:00Z&limit=1&order_by=asc",
						"market=KRW-BTC&state=done&start_time=2026-07-01T01%3A00%3A00Z"
								+ "&end_time=2026-07-08T01%3A00%3A00Z&limit=1&order_by=asc"),
				new QueryCase(new UpbitClosedOrdersRequest(null, "cancel", null, null, null, 1000, "desc"),
						"state=cancel&limit=1000&order_by=desc", "state=cancel&limit=1000&order_by=desc"),
				new QueryCase(new UpbitClosedOrdersRequest(null, null, List.of("cancel"), "0", "1", 1000, "asc"),
						"states[]=cancel&start_time=1970-01-01T00:00:00Z"
								+ "&end_time=1970-01-01T00:00:00.001Z&limit=1000&order_by=asc",
						"states%5B%5D=cancel&start_time=1970-01-01T00%3A00%3A00Z"
								+ "&end_time=1970-01-01T00%3A00%3A00.001Z&limit=1000&order_by=asc"),
				new QueryCase(new UpbitClosedOrdersRequest("BTC-ETH", null, List.of("cancel", "done"),
						null, null, 1, "desc"),
						"market=BTC-ETH&states[]=cancel&states[]=done&limit=1&order_by=desc",
						"market=BTC-ETH&states%5B%5D=cancel&states%5B%5D=done&limit=1&order_by=desc"),
				new QueryCase(new UpbitClosedOrdersRequest(null, null, List.of("done"),
						"+10000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z", null, null),
						"states[]=done&start_time=+10000-01-01T00:00:00Z"
								+ "&end_time=+10000-01-01T00:00:00Z&limit=100&order_by=desc",
						"states%5B%5D=done&start_time=%2B10000-01-01T00%3A00%3A00Z"
								+ "&end_time=%2B10000-01-01T00%3A00%3A00Z&limit=100&order_by=desc"));
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
			reply(exchange, 200, "[]");
		})) {
			UpbitOrderClient client = server.client(true);
			for (int i = 0; i < cases.size(); i++) {
				QueryCase example = cases.get(i);
				assertThat(client.getClosedOrders(example.request()).orders()).isEmpty();
				assertThat(requests).hasSize(i + 1);
				assertThat(requests.get(i)).isEqualTo("GET /v1/orders/closed?" + example.encoded());
				assertThat(tokens.get(i)).startsWith("Bearer ");
				UpbitJwtSignerTest.verify(tokens.get(i).substring(7), example.signing());
			}
		}
	}

	@Test
	void rejectsNullClosedRequestAndMissingCredentialsWithoutSending() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, "[]");
		})) {
			AtomicBoolean credentialsRead = new AtomicBoolean();
			UpbitOrderClient client = server.client(true, () -> {
				credentialsRead.set(true);
				return KEYS;
			});
			expect(UpbitErrorCode.BAD_REQUEST, () -> client.getClosedOrders(null));
			assertThat(credentialsRead).isFalse();
			for (Map<String, String> keys : List.of(Map.<String, String>of(),
					Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS),
					Map.of("UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET),
					Map.of("UPBIT_ACCESS_KEY", " ", "UPBIT_SECRET_KEY", UpbitJwtSignerTest.SECRET),
					Map.of("UPBIT_ACCESS_KEY", UpbitJwtSignerTest.ACCESS, "UPBIT_SECRET_KEY", ""))) {
				try (UpbitOrderClient missing = new UpbitOrderClient(server.uri(true), () -> keys)) {
					expect(UpbitErrorCode.MISSING_CREDENTIALS,
							() -> missing.getClosedOrders(new UpbitClosedOrdersRequest()));
				}
			}
			try (UpbitOrderClient missing = new UpbitOrderClient(server.uri(true), () -> null)) {
				expect(UpbitErrorCode.MISSING_CREDENTIALS,
						() -> missing.getClosedOrders(new UpbitClosedOrdersRequest()));
			}
			assertThat(requests).hasValue(0);
		}
	}

	@Test
	void preservesClosedSummariesPrecisionOrderFeesTimesAndUnfilledCancellations() throws Exception {
		String cancelled = CLOSED_ORDER.replace(ID, "123e4567-e89b-12d3-a456-426614174001")
				.replace("\"done\"", "\"cancel\"");
		String unfilled = cancelled.replace("426614174001", "426614174002")
				.replace("0.0000000000000000000200", "0").replace("0.0000000000000000000100", "0")
				.replace("18446744073709551616", "0");
		List<String> requests = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			reply(exchange, 200, "[" + CLOSED_ORDER + "," + cancelled + "," + unfilled + "]");
		})) {
			var snapshot = server.client(true).getClosedOrders(new UpbitClosedOrdersRequest());
			assertThat(snapshot.serviceId()).isEqualTo("upbit");
			assertThat(snapshot.orders()).hasSize(3);
			assertThat(snapshot.possiblyTruncated()).isFalse();
			var done = snapshot.orders().getFirst();
			assertThat(done.orderId()).isEqualTo(ID);
			assertThat(done.market()).isEqualTo("KRW-BTC");
			assertThat(done.side()).isEqualTo("bid");
			assertThat(done.orderType()).isEqualTo("limit");
			assertThat(done.state()).isEqualTo("done");
			assertThat(done.executedVolume()).isEqualTo("0.0000000000000000000200");
			assertThat(done.paidFee()).isEqualTo("0.0000000000000000000100");
			assertThat(done.createdAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
			assertThat(done.tradesCount()).isEqualTo(new BigInteger("18446744073709551616"));
			assertThat(done.feeCurrency()).isEmpty();
			assertThat(snapshot.orders().get(1).state()).isEqualTo("cancel");
			assertThat(snapshot.orders().get(1).executedVolume()).isEqualTo(done.executedVolume());
			assertThat(snapshot.orders().get(1).paidFee()).isEqualTo(done.paidFee());
			var empty = snapshot.orders().get(2);
			assertThat(empty.state()).isEqualTo("cancel");
			assertThat(empty.executedVolume()).isEqualTo("0");
			assertThat(empty.paidFee()).isEqualTo("0");
			assertThat(empty.tradesCount()).isEqualTo(BigInteger.ZERO);
			assertThat(requests).containsExactly("GET /v1/orders/closed?" + CLOSED_QUERY);
		}
	}

	@Test
	void preservesPossibleTruncationAtEachRequestLimitWithoutAdditionalRequests() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, body.get());
		})) {
			UpbitOrderClient client = server.client(true);
			for (int limit : List.of(1, 100, 1000)) {
				List<String> orders = new ArrayList<>();
				for (int i = 0; i < limit; i++) {
					orders.add(CLOSED_ORDER.replace(ID, "00000000-0000-0000-0000-%012d".formatted(i)));
				}
				body.set("[" + String.join(",", orders) + "]");
				var snapshot = client.getClosedOrders(new UpbitClosedOrdersRequest(
						null, null, null, null, null, limit, null));
				assertThat(snapshot.orders()).hasSize(limit);
				assertThat(snapshot.possiblyTruncated()).isTrue();
			}
			assertThat(requests).hasValue(3);
		}
	}

	@Test
	void rejectsClosedOrdersOutsideRequestedMarketOrStatesWithOneGetPerCall() throws Exception {
		record Scope(UpbitClosedOrdersRequest request, String matching, String mismatch) { }
		List<Scope> scopes = new ArrayList<>();
		scopes.add(new Scope(new UpbitClosedOrdersRequest("KRW-BTC", null, null, null, null, null, null),
				CLOSED_ORDER, CLOSED_ORDER.replace("KRW-BTC", "KRW-ETH")));
		for (String state : List.of("done", "cancel")) {
			String matching = CLOSED_ORDER.replace("\"done\"", "\"" + state + "\"");
			String mismatch = CLOSED_ORDER.replace("\"done\"", state.equals("done") ? "\"cancel\"" : "\"done\"");
			scopes.add(new Scope(new UpbitClosedOrdersRequest(null, state, null, null, null, null, null),
					matching, mismatch));
			scopes.add(new Scope(new UpbitClosedOrdersRequest(null, null, List.of(state), null, null, null, null),
					matching, mismatch));
		}
		AtomicReference<String> body = new AtomicReference<>();
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			reply(exchange, 200, withIgnoredBytes(body.get(), "safe".getBytes(StandardCharsets.UTF_8),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client(true);
			int calls = 0;
			for (Scope scope : scopes) {
				body.set("[" + scope.matching() + "]");
				assertThat(client.getClosedOrders(scope.request()).orders()).hasSize(1);
				assertThat(requests).hasSize(++calls);
				String later = scope.mismatch().replace(ID, "123e4567-e89b-12d3-a456-426614174001");
				for (String json : List.of("[" + later + "]", "[" + scope.matching() + "," + later + "]")) {
					body.set(json);
					expect(UpbitErrorCode.INVALID_RESPONSE, () -> client.getClosedOrders(scope.request()), tokens);
					assertThat(requests).hasSize(++calls);
				}
			}
			assertThat(requests).allSatisfy(target -> assertThat(target).startsWith("GET /v1/orders/closed?"));
		}
	}

	@Test
	void allowsMixedMarketsAndClosedStatesWhenTheRequestSelectsBothStates() throws Exception {
		String cancelled = CLOSED_ORDER.replace(ID, "123e4567-e89b-12d3-a456-426614174001")
				.replace("KRW-BTC", "KRW-ETH").replace("\"done\"", "\"cancel\"");
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, "[" + CLOSED_ORDER + "," + cancelled + "]");
		})) {
			UpbitOrderClient client = server.client(true);
			int calls = 0;
			for (var request : List.of(new UpbitClosedOrdersRequest(),
					new UpbitClosedOrdersRequest(null, null, List.of("done", "cancel"), null, null, null, null),
					new UpbitClosedOrdersRequest(null, null, List.of("cancel", "done"), null, null, null, null))) {
				var orders = client.getClosedOrders(request).orders();
				assertThat(orders).extracting("market").containsExactly("KRW-BTC", "KRW-ETH");
				assertThat(orders).extracting("state").containsExactly("done", "cancel");
				assertThat(orders.get(1).executedVolume()).isEqualTo("0.0000000000000000000200");
				assertThat(requests).hasValue(++calls);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 20_000})
	void boundsClosedResponseTokensWithOneGetAndNoPartialResults(int overflow) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			reply(exchange, 200, withIgnoredBytes(body.get(), "valid".getBytes(StandardCharsets.UTF_8),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client(true);
			int calls = 0;
			int length = 256 + overflow;
			int arrays = 62 + overflow; // Root array and summary object count too.
			List<String> fields = overflow > 1
					? List.of("\"policyBoundary\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays))
					: List.of("\"policyBoundary\":\"" + "\\u0078".repeat(length) + "\"",
							"\"" + "\\u0078".repeat(length) + "\":null",
							"\"policyBoundary\":{\"nested\":[{\"value\":\"" + "x".repeat(length) + "\"}]}",
							"\"policyBoundary\":{\"" + "x".repeat(length) + "\":null}",
							"\"policyBoundary\":1." + "0".repeat(122 + overflow) + "e+00",
							"\"policyBoundary\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays));
			for (String field : fields) {
				String second = CLOSED_ORDER.replace(ID, "123e4567-e89b-12d3-a456-426614174001");
				int end = second.lastIndexOf('}');
				body.set("[" + CLOSED_ORDER + "," + second.substring(0, end) + "," + field + second.substring(end) + "]");
				assertThat(body.get().length()).isLessThan(UpbitClosedOrdersMapper.MAX_JSON_LENGTH);
				if (overflow == 0) {
					assertThat(client.getClosedOrders(new UpbitClosedOrdersRequest()).orders())
							.extracting(order -> order.orderId()).containsExactly(ID, "123e4567-e89b-12d3-a456-426614174001");
				}
				else {
					expect(UpbitErrorCode.INVALID_RESPONSE,
							() -> client.getClosedOrders(new UpbitClosedOrdersRequest()), tokens, ID);
				}
				assertThat(requests).hasSize(++calls).allSatisfy(target ->
						assertThat(target).isEqualTo("GET /v1/orders/closed?" + CLOSED_QUERY));
			}
		}
	}

	@Test
	void rejectsInvalidClosedResponsesWithoutPartialResultsOrSensitiveDiagnostics() throws Exception {
		String second = CLOSED_ORDER.replace(ID, "123e4567-e89b-12d3-a456-426614174001");
		List<String> invalid = new ArrayList<>(List.of("[", "null", "{}", "[{}]", BODY_MARKER,
				"[" + CLOSED_ORDER + "," + CLOSED_ORDER + "]",
				"[" + CLOSED_ORDER + "," + CLOSED_ORDER.replace(ID, UPPER_ID) + "]",
				"[" + CLOSED_ORDER + "," + second.replace("\"paid_fee\":", "\"missing\":") + "]",
				"[" + CLOSED_ORDER.replace("\"market\":", "\"market\":\"duplicate\",\"market\":") + "]"));
		for (String field : List.of("uuid", "market", "side", "ord_type", "state", "created_at",
				"executed_volume", "paid_fee", "trades_count")) {
			invalid.add("[" + CLOSED_ORDER.replace("\"" + field + "\":", "\"missing\":") + "]");
		}
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			String json = body.get();
			if (BODY_MARKER.equals(json)) {
				json += UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token;
			}
			reply(exchange, 200, json);
		})) {
			UpbitOrderClient client = server.client(true);
			for (String json : invalid) {
				body.set(json);
				expect(UpbitErrorCode.INVALID_RESPONSE,
						() -> client.getClosedOrders(new UpbitClosedOrdersRequest()), tokens);
			}
			body.set("[" + CLOSED_ORDER + "," + second + "]");
			expect(UpbitErrorCode.INVALID_RESPONSE, () -> client.getClosedOrders(
					new UpbitClosedOrdersRequest(null, null, null, null, null, 1, null)), tokens);
			assertThat(requests).hasValue(invalid.size() + 1);
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
	@CsvSource({"KRW-BTC,bid,KRW-ETH,bid", "KRW-ETH,bid,KRW-BTC,bid",
			"KRW-BTC,bid,KRW-BTC,ask", "KRW-BTC,ask,KRW-BTC,bid"})
	void rejectsMismatchedFillsWithOneGetAndNoSensitiveDiagnostics(
			String market, String side, String tradeMarket, String tradeSide) throws Exception {
		String fill = """
				{"uuid":"fill-1","market":"%s","side":"%s","price":"1.2300",
				"volume":"0.0200","funds":"0.024600","created_at":"2026-07-01T01:00:00Z"}
				""".formatted(market, side);
		String mismatch = fill.replace("fill-1", "fill-2")
				.replace("\"market\":\"" + market + "\"", "\"market\":\"" + tradeMarket + "\"")
				.replace("\"side\":\"" + side + "\"", "\"side\":\"" + tradeSide + "\"");
		AtomicReference<String> trades = new AtomicReference<>();
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			String body = ORDER.replace("KRW-BTC", market).replace("\"bid\"", "\"" + side + "\"")
					.replace("\"trades\":[]", "\"trades\":[" + trades.get() + "]");
			reply(exchange, 200, withIgnoredBytes(body, "valid".getBytes(StandardCharsets.UTF_8),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client();
			int calls = 0;
			for (String value : List.of(mismatch, fill + "," + mismatch)) {
				trades.set(value);
				expect(UpbitErrorCode.INVALID_RESPONSE, () -> client.getOrder(ID), tokens);
				assertThat(requests).hasSize(++calls).allSatisfy(request ->
						assertThat(request).isEqualTo("GET /v1/order?uuid=" + ID));
			}
		}
	}

	@Test
	void rejectsOverPolicyTokensInHttp200WithOneGetAndSanitizedErrors() throws Exception {
		AtomicReference<String> extra = new AtomicReference<>();
		List<String> requests = new CopyOnWriteArrayList<>();
		List<String> tokens = new CopyOnWriteArrayList<>();
		String fill = """
				{"uuid":"fill-1","market":"KRW-BTC","side":"bid","price":"1.2300",
				"volume":"0.0200","funds":"0.024600","created_at":"2026-07-01T01:00:00Z"}
				""";
		String order = ORDER.replace("\"trades\":[]", "\"trades\":[" + fill + "]");
		try (LocalServer server = new LocalServer(exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			int end = order.lastIndexOf('}');
			String body = order.substring(0, end) + "," + extra.get() + order.substring(end);
			reply(exchange, 200, withIgnoredBytes(body, "valid".getBytes(StandardCharsets.UTF_8),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client();
			int calls = 0;
			for (int overflow : List.of(0, 1)) {
				int stringLength = 256 + overflow;
				int numberLength = 128 + overflow;
				int arrays = 63 + overflow; // Root object adds one to the depth.
				for (String field : List.of("\"ignored\":\"" + "\\u0078".repeat(stringLength) + "\"",
						"\"" + "x".repeat(stringLength) + "\":null",
						"\"ignored\":0." + "0".repeat(numberLength - 3) + "1",
						"\"ignored\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays))) {
					extra.set(field);
					if (overflow == 0) {
						assertThat(client.getOrder(ID).trades()).hasSize(1);
					}
					else {
						expect(UpbitErrorCode.INVALID_RESPONSE, () -> client.getOrder(ID), tokens);
					}
					assertThat(requests).hasSize(++calls).allSatisfy(request ->
							assertThat(request).isEqualTo("GET /v1/order?uuid=" + ID));
				}
			}
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
						() -> server.client(false, () -> keys).getOrder(ID));
			}
			assertThat(requests).hasValue(0);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rejectsMalformedUtf8InIgnoredSuccessFieldsWithoutPartialResults(boolean closed) throws Exception {
		AtomicReference<byte[]> invalid = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			// For closed orders, the damaged field follows a complete, valid first result.
			String json = closed ? "[" + CLOSED_ORDER + ","
					+ CLOSED_ORDER.replace(ID, "123e4567-e89b-12d3-a456-426614174001") + "]" : ORDER;
			reply(exchange, 200, withIgnoredBytes(json, invalid.get(),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client(closed);
			// The same fields and secrets must pass when only the injected bytes are valid.
			invalid.set("valid".getBytes(StandardCharsets.UTF_8));
			if (closed) {
				assertThat(client.getClosedOrders(new UpbitClosedOrdersRequest()).orders())
						.extracting(order -> order.orderId())
						.containsExactly(ID, "123e4567-e89b-12d3-a456-426614174001");
			}
			else {
				assertSuccessfulGet(client, false);
			}
			assertThat(requests).hasValue(1);
			int calls = 1;
			for (byte[] bytes : malformedUtf8()) {
				invalid.set(bytes);
				expect(UpbitErrorCode.INVALID_RESPONSE, () -> get(client, closed), tokens);
				assertThat(requests).hasValue(++calls);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void acceptsValidNonAsciiAndEncodedReplacementCharacterInIgnoredFields(boolean closed) throws Exception {
		AtomicReference<byte[]> value = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			reply(exchange, 200, withIgnoredBytes(successBody(closed), value.get(), BODY_MARKER));
		})) {
			UpbitOrderClient client = server.client(closed);
			int calls = 0;
			for (String text : List.of("한글 \uD83D\uDE00", "\uFFFD")) {
				value.set(text.getBytes(StandardCharsets.UTF_8));
				assertSuccessfulGet(client, closed);
				assertThat(requests).hasValue(++calls);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesHttpCategoriesWhenPermissionErrorBodyHasMalformedUtf8(boolean closed) throws Exception {
		Map<Integer, UpbitErrorCode> cases = Map.ofEntries(
				Map.entry(400, UpbitErrorCode.BAD_REQUEST), Map.entry(401, UpbitErrorCode.AUTHENTICATION_FAILED),
				Map.entry(403, UpbitErrorCode.PERMISSION_DENIED), Map.entry(404, UpbitErrorCode.ORDER_NOT_FOUND),
				Map.entry(418, UpbitErrorCode.IP_BLOCKED), Map.entry(429, UpbitErrorCode.RATE_LIMITED),
				Map.entry(302, UpbitErrorCode.REDIRECT_REJECTED), Map.entry(307, UpbitErrorCode.REDIRECT_REJECTED),
				Map.entry(500, UpbitErrorCode.SERVER_ERROR), Map.entry(503, UpbitErrorCode.SERVER_ERROR),
				Map.entry(599, UpbitErrorCode.SERVER_ERROR));
		AtomicInteger status = new AtomicInteger();
		AtomicReference<byte[]> invalid = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			reply(exchange, status.get(), withIgnoredBytes("{\"error\":{\"name\":\"out_of_scope\"}}",
					invalid.get(), BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client(closed);
			int calls = 0;
			// With valid bytes, the same escaped fields must preserve permission parsing.
			invalid.set("권한 \uFFFD".getBytes(StandardCharsets.UTF_8));
			for (int permissionStatus : List.of(401, 403)) {
				status.set(permissionStatus);
				expect(UpbitErrorCode.PERMISSION_DENIED, () -> get(client, closed), tokens);
				assertThat(requests).hasValue(++calls);
			}
			for (byte[] bytes : malformedUtf8()) {
				invalid.set(bytes);
				for (var entry : cases.entrySet()) {
					status.set(entry.getKey());
					expect(entry.getValue(), () -> get(client, closed), tokens);
					assertThat(requests).hasValue(++calls);
				}
			}
		}
	}

	private static List<byte[]> malformedUtf8() {
		return List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xC3, 0x28},
				new byte[] {(byte) 0xC0, (byte) 0xAF}, new byte[] {(byte) 0xE2, (byte) 0x82});
	}

	private static byte[] withIgnoredBytes(String json, byte[] value, String secrets) {
		// Insert in the final order for arrays, leaving an earlier valid result intact.
		int object = json.lastIndexOf("{\"uuid\":");
		int start = (object >= 0 ? object : json.indexOf('{')) + 1;
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
	@ValueSource(booleans = {false, true})
	void classifiesHttpFailuresAndNeverRetainsResponseCredentialsOrToken(boolean closed) throws Exception {
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
			UpbitOrderClient client = server.client(closed);
			for (var entry : cases.entrySet()) {
				status.set(entry.getKey());
				expect(entry.getValue(), () -> get(client, closed), tokens);
			}
			assertThat(requests).hasValue(cases.size());
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rejectsAmbiguousOrIncompletePermissionDocuments(boolean closed) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			assertThat(exchange.getRequestMethod() + " " + exchange.getRequestURI()).isEqualTo(requestTargetWithMethod(closed));
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			// Prepend bounded secrets without repairing malformed tails or changing duplicate keys.
			reply(exchange, 401, body.get().startsWith("{")
					? withIgnoredBytes(body.get(), "valid".getBytes(StandardCharsets.UTF_8),
							BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token)
					: body.get().getBytes(StandardCharsets.UTF_8));
		})) {
			UpbitOrderClient client = server.client(closed);
			int calls = 0;
			for (String json : rejectedPermissionBodies()) {
				body.set(json);
				expect(UpbitErrorCode.AUTHENTICATION_FAILED, () -> get(client, closed), tokens);
				assertThat(requests).hasValue(++calls);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void boundsEveryPermissionTokenBeforeAndAfterTheName(boolean closed) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		List<String> tokens = new CopyOnWriteArrayList<>();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			assertThat(exchange.getRequestMethod() + " " + exchange.getRequestURI()).isEqualTo(requestTargetWithMethod(closed));
			String token = exchange.getRequestHeaders().getFirst("Authorization");
			tokens.add(token);
			reply(exchange, 401, withIgnoredBytes(body.get(), "valid".getBytes(StandardCharsets.UTF_8),
					BODY_MARKER + UpbitJwtSignerTest.ACCESS + UpbitJwtSignerTest.SECRET + token));
		})) {
			UpbitOrderClient client = server.client(closed);
			int calls = 0;
			for (int overflow : List.of(0, 1)) {
				for (String field : permissionPolicyFields(overflow)) {
					for (boolean permissionFirst : List.of(false, true)) {
						String permission = "\"error\":{\"name\":\"out_of_scope\"}";
						body.set("{" + (permissionFirst ? permission + "," + field : field + "," + permission) + "}");
						expect(overflow == 0 ? UpbitErrorCode.PERMISSION_DENIED : UpbitErrorCode.AUTHENTICATION_FAILED,
								() -> get(client, closed), tokens);
						assertThat(requests).hasValue(++calls);
					}
				}
			}
		}
	}

	private static String requestTargetWithMethod(boolean closed) {
		return "GET " + requestTarget(closed);
	}

	private static List<String> permissionPolicyFields(int overflow) {
		int length = 256 + overflow;
		int numberLength = 128 + overflow;
		int arrays = 63 + overflow; // Root object is depth one.
		return List.of("\"policyBoundary\":\"" + "x".repeat(length) + "\"",
				"\"policyBoundary\":\"" + "\\u0078".repeat(length) + "\"",
				"\"policyBoundary\":\"" + "\\uD83D\\uDE00".repeat(128) + "x".repeat(overflow) + "\"",
				"\"" + "x".repeat(length) + "\":null",
				"\"" + "\\u0078".repeat(length) + "\":null",
				"\"policyBoundary\":{\"nested\":[{\"value\":\"" + "x".repeat(length) + "\"}]}",
				"\"policyBoundary\":" + "1".repeat(numberLength),
				"\"policyBoundary\":-" + "1".repeat(numberLength - 1),
				"\"policyBoundary\":1." + "0".repeat(numberLength - 6) + "e+00",
				"\"policyBoundary\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays),
				"\"policyBoundary\":" + "{\"x\":".repeat(arrays) + "0" + "}".repeat(arrays));
	}

	private static List<String> rejectedPermissionBodies() {
		String permission = "{\"error\":{\"name\":\"out_of_scope\"}}";
		return List.of("", "null", "[]", "[" + permission + "]", "\"out_of_scope\"", "0", "true", "{}",
				"{\"error\":null}", "{\"error\":[]}", "{\"error\":\"out_of_scope\"}", "{\"error\":1}",
				"{\"error\":true}", "{\"error\":{}}", "{\"error\":{\"name\":null}}",
				"{\"error\":{\"name\":[\"out_of_scope\"]}}", "{\"error\":{\"name\":{\"name\":\"out_of_scope\"}}}",
				"{\"error\":{\"name\":1}}", "{\"error\":{\"name\":true}}",
				"{\"error\":{\"name\":\"OUT_OF_SCOPE\"}}", "{\"error\":{\"name\":\"out_of_scope \"}}",
				"{\"name\":\"out_of_scope\"}", "{\"other\":{\"error\":{\"name\":\"out_of_scope\"}}}",
				"{\"error\":{\"nested\":{\"name\":\"out_of_scope\"}}}",
				"{\"error\":{\"name\":\"unknown\",\"nested\":{\"name\":\"out_of_scope\"}}}",
				"{\"error\":{\"name\":\"unknown\"},\"other\":{\"name\":\"out_of_scope\"}}",
				"{\"error\":{\"name\":\"out_of_scope\"},\"error\":{\"name\":\"out_of_scope\"}}",
				"{\"error\":{},\"error\":{\"name\":\"out_of_scope\"}}",
				"{\"error\":{\"name\":\"out_of_scope\"},\"error\":{}}",
				"{\"error\":{\"name\":\"unknown\",\"name\":\"out_of_scope\"}}",
				"{\"error\":{\"name\":\"out_of_scope\",\"name\":\"out_of_scope\"}}",
				"{\"error\":{\"name\":\"out_of_scope\",\"na\\u006de\":\"unknown\"}}",
				"{\"error\":{\"name\":\"out_of_scope\"},\"err\\u006fr\":{}}",
				"{\"error\":{\"name\":\"out_of_scope\"},\"extra\":{\"x\":1,\"x\":2}}",
				permission + " {}", permission + " []", permission + " null", permission + " 0",
				permission + " true", permission + " {", permission + " garbage", permission + " \"",
				permission.substring(0, permission.length() - 1),
				"{\"error\":{\"name\":\"out_of_scope\"},\"tail\":[}");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void recognizesPermissionErrorsInBoth401And403(boolean closed) throws Exception {
		AtomicInteger status = new AtomicInteger(401);
		AtomicReference<String> body = new AtomicReference<>();
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			assertThat(exchange.getRequestMethod() + " " + exchange.getRequestURI()).isEqualTo(requestTargetWithMethod(closed));
			reply(exchange, status.get(), body.get());
		})) {
			UpbitOrderClient client = server.client(closed);
			int calls = 0;
			for (int code : List.of(401, 403)) {
				status.set(code);
				for (String name : List.of("out_of_scope", "insufficient_permission", "insufficient_permissions",
						"permission_denied", "no_authorization_ip")) {
					for (String fields : List.of("\"error\":{\"name\":\"" + name + "\"}",
							"\"error\":{\"nested\":{\"name\":\"unknown\"},\"name\":\"" + name + "\"}",
							"\"err\\u006fr\":{\"na\\u006de\":\"" + name.replace("_", "\\u005f") + "\"}")) {
						body.set("{" + fields + ",\"message\":\"" + BODY_MARKER + "\","
								+ "\"name\":\"unknown\",\"other\":{\"name\":\"unknown\"}} \n\t");
						expect(UpbitErrorCode.PERMISSION_DENIED, () -> get(client, closed));
						assertThat(requests).hasValue(++calls);
					}
				}
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void neverFollowsRedirectOrForwardsAuthorizationToAnotherServer(boolean closed) throws Exception {
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
			expect(UpbitErrorCode.REDIRECT_REJECTED, () -> get(source.client(closed), closed));
			assertThat(sourceRequests).hasValue(1);
			assertThat(targetRequests).hasValue(0);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void boundsChunkedBodyWithoutTrustingContentLengthAndAcceptsExactlyOneMiB(boolean closed) throws Exception {
		AtomicInteger size = new AtomicInteger(UpbitOrderClient.MAX_RESPONSE_BYTES);
		AtomicInteger requests = new AtomicInteger();
		try (LocalServer server = new LocalServer(exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, 0); // chunked, no Content-Length
			try (var output = exchange.getResponseBody()) {
				byte[] body = successBody(closed).getBytes(StandardCharsets.UTF_8);
				output.write(body);
				int padding = size.get() - body.length;
				byte[] spaces = " ".repeat(4096).getBytes(StandardCharsets.UTF_8);
				while (padding > 0) {
					int chunk = Math.min(spaces.length, padding);
					output.write(spaces, 0, chunk);
					padding -= chunk;
				}
			}
		})) {
			UpbitOrderClient client = server.client(closed);
			assertSuccessfulGet(client, closed);
			size.incrementAndGet();
			expect(UpbitErrorCode.RESPONSE_TOO_LARGE, () -> get(client, closed));
			assertThat(requests).hasValue(2);
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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void appliesTenSecondDeadlineToBodyEvenAfterHeadersArrive(boolean closed) throws Exception {
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
			UpbitOrderClient client = server.client(closed);
			long started = System.nanoTime();
			try {
				expect(UpbitErrorCode.TIMEOUT, () -> get(client, closed));
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
	void cancelsClosedOrdersConnectionWhenBodyDeadlineExpires() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
				UpbitOrderClient client = new UpbitOrderClient(
						URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1/orders/closed"), () -> KEYS)) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					socket.setSoTimeout(15000);
					var input = new BufferedReader(new InputStreamReader(socket.getInputStream(),
							StandardCharsets.US_ASCII));
					assertThat(input.readLine()).isEqualTo("GET " + requestTarget(true) + " HTTP/1.1");
					requests.incrementAndGet();
					String line;
					while ((line = input.readLine()) != null && !line.isEmpty()) {
						// Consume headers, then leave a chunked JSON body incomplete.
					}
					var output = socket.getOutputStream();
					output.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n[\r\n")
							.getBytes(StandardCharsets.US_ASCII));
					output.flush();
					// Observe cancellation before closing the client or server test resources.
					assertThat(input.read()).isEqualTo(-1);
				}
				return null;
			});
			long started = System.nanoTime();
			expect(UpbitErrorCode.TIMEOUT, () -> client.getClosedOrders(new UpbitClosedOrdersRequest()));
			assertThat(Duration.ofNanos(System.nanoTime() - started))
					.isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
			receiver.get(3, TimeUnit.SECONDS);
			assertThat(requests).hasValue(1);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void sanitizesMalformedSuccessNetworkFailureAndEnvironmentFailure(boolean closed) throws Exception {
		try (LocalServer server = new LocalServer(exchange -> reply(exchange, 200, BODY_MARKER))) {
			expect(UpbitErrorCode.INVALID_RESPONSE, () -> get(server.client(closed), closed));
			expect(UpbitErrorCode.MISSING_CREDENTIALS, () -> get(server.client(closed, () -> {
				throw new IllegalStateException(BODY_MARKER + UpbitJwtSignerTest.SECRET);
			}), closed));
		}
		int unusedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			unusedPort = socket.getLocalPort();
		}
		try (UpbitOrderClient disconnected = new UpbitOrderClient(
				URI.create("http://127.0.0.1:" + unusedPort + "/v1/order"), () -> KEYS)) {
			expect(UpbitErrorCode.NETWORK_ERROR, () -> get(disconnected, closed));
		}
	}

	@Test
	void testSeamRefusesRemoteHostsAndOtherPaths() {
		for (String endpoint : List.of("https://api.upbit.com/v1/order", "http://example.com/v1/order",
				"http://127.0.0.1/v1/orders", "http://127.0.0.1/v1/order?uuid=x",
				"http://127.0.0.1/v1/order#x", "http://user@127.0.0.1/v1/order",
				"https://127.0.0.1/v1/orders/closed", "http://example.com/v1/orders/closed",
				"http://127.0.0.1/v1/orders/closed/", "http://127.0.0.1/v1/orders/closed?state=done",
				"http://127.0.0.1/v1/orders/closed#x", "http://user@127.0.0.1/v1/orders/closed")) {
			assertThatThrownBy(() -> new UpbitOrderClient(URI.create(endpoint), () -> KEYS))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new UpbitOrderClient(null, () -> KEYS))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static String successBody(boolean closed) {
		return closed ? "[" + CLOSED_ORDER + "]" : ORDER;
	}

	private static String requestTarget(boolean closed) {
		return closed ? "/v1/orders/closed?" + CLOSED_QUERY : "/v1/order?uuid=" + ID;
	}

	private static void get(UpbitOrderClient client, boolean closed) {
		if (closed) {
			client.getClosedOrders(new UpbitClosedOrdersRequest());
		}
		else {
			client.getOrder(ID);
		}
	}

	private static void assertSuccessfulGet(UpbitOrderClient client, boolean closed) {
		if (closed) {
			var orders = client.getClosedOrders(new UpbitClosedOrdersRequest()).orders();
			assertThat(orders).hasSize(1);
			assertThat(orders.getFirst().orderId()).isEqualTo(ID);
		}
		else {
			assertThat(client.getOrder(ID).orderId()).isEqualTo(ID);
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
					.doesNotContain(UpbitJwtSignerTest.ACCESS, UpbitJwtSignerTest.SECRET, BODY_MARKER,
							"Bearer ", "eyJ", "StreamReadException", "Duplicate Object property",
							"MalformedInputException", "UnmappableCharacterException", "CharacterCodingException", "Input length",
							"StreamConstraintsException", "JsonParseException", "[Source:", "line: ", "column: ");
			if (responseIds.length > 0) {
				assertThat(exception.getMessage() + exception + trace).doesNotContain(responseIds);
			}
			for (String token : tokens) {
				assertThat(trace.toString()).doesNotContain(token, token.substring(7));
			}
		});
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		reply(exchange, status, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
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
		private final List<UpbitOrderClient> clients = new ArrayList<>();

		LocalServer(HttpHandler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", handler);
			server.setExecutor(executor);
			server.start();
		}

		URI uri() {
			return uri(false);
		}

		URI uri(boolean closed) {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
					+ (closed ? "/v1/orders/closed" : "/v1/order"));
		}

		UpbitOrderClient client() {
			return client(false);
		}

		UpbitOrderClient client(boolean closed) {
			return client(closed, () -> KEYS);
		}

		UpbitOrderClient client(boolean closed, Supplier<Map<String, String>> environment) {
			UpbitOrderClient client = new UpbitOrderClient(uri(closed), environment);
			clients.add(client);
			return client;
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
			clients.forEach(UpbitOrderClient::close);
		}
	}
}
