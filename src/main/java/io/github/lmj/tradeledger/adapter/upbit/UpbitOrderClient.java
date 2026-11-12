package io.github.lmj.tradeledger.adapter.upbit;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.lmj.tradeledger.application.model.ClosedOrdersSnapshot;
import io.github.lmj.tradeledger.application.model.OrderExecutionSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A single authenticated GET of one order or closed order summaries. Reads
 * UPBIT_ACCESS_KEY and UPBIT_SECRET_KEY from the process environment on each call.
 * No retries or pagination.
 * The JVM must start with {@code -Djdk.httpclient.disableRetryConnect=true} and
 * {@code -Djdk.httpclient.redirects.retrylimit=1}; otherwise construction fails closed.
 * Setting properties here would be unsafe because JDK HTTP classes cache them and
 * another client may already have initialized those classes.
 */
public final class UpbitOrderClient implements AutoCloseable {

	// Project resource policies, not official Upbit limits.
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private static final URI ENDPOINT = URI.create("https://api.upbit.com/v1/order");
	private static final URI CLOSED_ENDPOINT = URI.create("https://api.upbit.com/v1/orders/closed");
	private static final Pattern UUID = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> PERMISSION_ERRORS = Set.of("out_of_scope", "insufficient_permission",
			"insufficient_permissions", "permission_denied", "no_authorization_ip");

	private final URI endpoint;
	private final URI closedEndpoint;
	private final Supplier<Map<String, String>> environment;
	private final HttpClient http;
	private final UpbitJwtSigner signer = new UpbitJwtSigner();
	private final UpbitOrderMapper mapper = new UpbitOrderMapper();
	private final UpbitClosedOrdersMapper closedMapper = new UpbitClosedOrdersMapper();

	public UpbitOrderClient() {
		this(ENDPOINT, System::getenv, false);
	}

	/** Internal test seam: credentials are fake and the destination must be loopback. */
	UpbitOrderClient(URI endpoint, Supplier<Map<String, String>> environment) {
		this(endpoint, environment, true);
	}

	private UpbitOrderClient(URI endpoint, Supplier<Map<String, String>> environment, boolean test) {
		if (test && (endpoint == null || !"http".equals(endpoint.getScheme()) || endpoint.getHost() == null
				|| !Set.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost())
				|| !Set.of(ENDPOINT.getRawPath(), CLOSED_ENDPOINT.getRawPath()).contains(endpoint.getRawPath())
				|| endpoint.getRawQuery() != null
				|| endpoint.getRawFragment() != null || endpoint.getRawUserInfo() != null)) {
			throw new IllegalArgumentException("Invalid local Upbit test endpoint");
		}
		this.endpoint = endpoint.resolve(ENDPOINT.getRawPath());
		this.closedEndpoint = endpoint.resolve(CLOSED_ENDPOINT.getRawPath());
		this.environment = environment;
		try {
			requireNoRetryConfiguration(ManagementFactory.getRuntimeMXBean().getInputArguments(),
					System.getProperty("jdk.httpclient.disableRetryConnect"),
					System.getProperty("jdk.httpclient.redirects.retrylimit"));
		}
		catch (RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION);
		}
		this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	/** Release the owned HTTP client and its connection/worker resources. */
	@Override
	public void close() {
		http.close();
	}

	// Startup values matter: checking only mutable system properties cannot establish
	// what a previously initialized JDK client cached. The retry limit bounds attempts,
	// including retries after a connection closes before response headers arrive.
	static void requireNoRetryConfiguration(List<String> arguments, String disableRetry, String retryLimit) {
		Map<String, String> required = Map.of("jdk.httpclient.disableRetryConnect", "true",
				"jdk.httpclient.redirects.retrylimit", "1");
		for (var entry : required.entrySet()) {
			String prefix = "-D" + entry.getKey() + "=";
			List<String> settings = arguments.stream().filter(argument -> argument.startsWith(prefix)).toList();
			if (settings.isEmpty()
					|| settings.stream().anyMatch(argument -> !argument.equals(prefix + entry.getValue()))) {
				throw new UpbitApiException(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION);
			}
		}
		if (!"true".equals(disableRetry) || !"1".equals(retryLimit)) {
			throw new UpbitApiException(UpbitErrorCode.UNSAFE_TRANSPORT_CONFIGURATION);
		}
	}

	public OrderExecutionSnapshot getOrder(String uuid) {
		if (uuid == null || !UUID.matcher(uuid).matches()) {
			throw new UpbitApiException(UpbitErrorCode.INVALID_UUID);
		}
		String json = get(endpoint, List.of(Map.entry("uuid", uuid)));
		try {
			OrderExecutionSnapshot snapshot = mapper.map(json);
			if (!UUID.matcher(snapshot.orderId()).matches() || !uuid.equalsIgnoreCase(snapshot.orderId())) {
				throw new UpbitApiException(UpbitErrorCode.INVALID_RESPONSE);
			}
			return snapshot;
		}
		catch (RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.INVALID_RESPONSE);
		}
	}

	public ClosedOrdersSnapshot getClosedOrders(UpbitClosedOrdersRequest request) {
		if (request == null) {
			throw new UpbitApiException(UpbitErrorCode.BAD_REQUEST);
		}
		List<Map.Entry<String, String>> parameters = new ArrayList<>();
		if (request.market() != null) {
			parameters.add(Map.entry("market", request.market()));
		}
		if (request.state() != null) {
			parameters.add(Map.entry("state", request.state()));
		}
		else {
			for (String state : request.states()) {
				parameters.add(Map.entry("states[]", state));
			}
		}
		if (request.startTime() != null) {
			parameters.add(Map.entry("start_time", request.startTime()));
			parameters.add(Map.entry("end_time", request.endTime()));
		}
		parameters.add(Map.entry("limit", request.limit().toString()));
		parameters.add(Map.entry("order_by", request.orderBy()));
		String json = get(closedEndpoint, parameters);
		try {
			return closedMapper.map(json, request);
		}
		catch (RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.INVALID_RESPONSE);
		}
	}

	private String get(URI destination, List<Map.Entry<String, String>> parameters) {
		// Both strings use the same ordered entries; repeated array keys must survive.
		String unencoded = parameters.stream().map(entry -> entry.getKey() + "=" + entry.getValue())
				.collect(Collectors.joining("&"));
		String encoded = parameters.stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
				.collect(Collectors.joining("&"));
		String token = sign(unencoded);
		HttpRequest request = HttpRequest.newBuilder(URI.create(destination.toASCIIString() + "?" + encoded))
				.timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + token)
				.header("Accept", "application/json").GET().build();
		return send(request);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String sign(String unencodedQuery) {
		String token;
		try {
			Map<String, String> values = environment.get();
			token = signer.sign(values.get("UPBIT_ACCESS_KEY"), values.get("UPBIT_SECRET_KEY"), unencodedQuery);
		}
		catch (UpbitApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.MISSING_CREDENTIALS);
		}
		return token;
	}

	private String send(HttpRequest request) {
		BoundedBodySubscriber body = new BoundedBodySubscriber();
		CompletableFuture<HttpResponse<byte[]>> response = null;
		try {
			long started = System.nanoTime();
			response = http.sendAsync(request, info -> body);
			// Unlike an InputStream body handler, this future completes only after the full body.
			HttpResponse<byte[]> received = response.get(
					Math.max(1, REQUEST_TIMEOUT.toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
			String json = new String(received.body(), StandardCharsets.UTF_8);
			if (received.statusCode() != 200) {
				throw new UpbitApiException(statusCode(received.statusCode(), json));
			}
			return json;
		}
		catch (TimeoutException exception) {
			throw new UpbitApiException(UpbitErrorCode.TIMEOUT);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new UpbitApiException(UpbitErrorCode.INTERRUPTED);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof UpbitApiException safe) {
				throw safe;
			}
			throw new UpbitApiException(cause instanceof HttpTimeoutException
					? UpbitErrorCode.TIMEOUT : UpbitErrorCode.NETWORK_ERROR);
		}
		catch (UpbitApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.NETWORK_ERROR);
		}
		finally {
			body.cancel();
			if (response != null && !response.isDone()) {
				response.cancel(true);
			}
		}
	}

	private static UpbitErrorCode statusCode(int status, String json) {
		if ((status == 401 || status == 403) && permissionError(json)) {
			return UpbitErrorCode.PERMISSION_DENIED;
		}
		if (status >= 300 && status < 400) {
			return UpbitErrorCode.REDIRECT_REJECTED;
		}
		if (status >= 500 && status < 600) {
			return UpbitErrorCode.SERVER_ERROR;
		}
		return switch (status) {
			case 400 -> UpbitErrorCode.BAD_REQUEST;
			case 401 -> UpbitErrorCode.AUTHENTICATION_FAILED;
			case 403 -> UpbitErrorCode.PERMISSION_DENIED;
			case 404 -> UpbitErrorCode.ORDER_NOT_FOUND;
			case 418 -> UpbitErrorCode.IP_BLOCKED;
			case 429 -> UpbitErrorCode.RATE_LIMITED;
			default -> UpbitErrorCode.UNEXPECTED_STATUS;
		};
	}

	private static boolean permissionError(String json) {
		try {
			JsonNode root = JSON.readTree(json);
			JsonNode error = root == null ? null : root.get("error");
			JsonNode name = error == null ? null : error.get("name");
			return name != null && name.isString() && PERMISSION_ERRORS.contains(name.asString());
		}
		catch (RuntimeException exception) {
			return false;
		}
	}

	static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private Flow.Subscription subscription;
		private boolean cancelled;

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		@Override
		public synchronized void onSubscribe(Flow.Subscription incoming) {
			if (cancelled || subscription != null) {
				incoming.cancel();
				return;
			}
			subscription = incoming;
			incoming.request(1);
		}

		@Override
		public synchronized void onNext(List<ByteBuffer> buffers) {
			if (cancelled || result.isDone()) {
				return;
			}
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
					result.completeExceptionally(new UpbitApiException(UpbitErrorCode.RESPONSE_TOO_LARGE));
					cancel();
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			// Transport exceptions can contain server-controlled text. Never retain them.
			result.completeExceptionally(new UpbitApiException(
					throwable instanceof HttpTimeoutException ? UpbitErrorCode.TIMEOUT : UpbitErrorCode.NETWORK_ERROR));
		}

		@Override
		public synchronized void onComplete() {
			if (!cancelled) {
				result.complete(bytes.toByteArray());
			}
		}

		synchronized void cancel() {
			cancelled = true;
			if (subscription != null) {
				subscription.cancel();
			}
		}
	}
}
