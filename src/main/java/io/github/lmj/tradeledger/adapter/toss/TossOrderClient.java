package io.github.lmj.tradeledger.adapter.toss;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import io.github.lmj.tradeledger.application.model.TossClosedOrdersSnapshot;
import io.github.lmj.tradeledger.application.model.TossOrderSnapshot;

/**
 * One read-only GET for an order or one CLOSED page at the fixed Toss origin.
 * Reads externally issued TOSS_ACCESS_TOKEN and TOSS_ACCOUNT_SEQ per call.
 * No token issuance/refresh, account discovery, redirects, retries or pagination.
 *
 * <p>Requires JVM startup options {@code -Djdk.httpclient.disableRetryConnect=true}
 * and {@code -Djdk.httpclient.redirects.retrylimit=1}; late properties cannot prove
 * cached JDK settings. Keep {@code jdk.httpclient.HttpClient.log} unset or empty
 * for the process lifetime. Checks cannot prevent concurrent property mutation.
 * Project limits: connect 3 seconds, whole response body 10 seconds, body 1 MiB.
 * Close this client to release its connections and workers.
 */
public final class TossOrderClient implements AutoCloseable {
	private static final URI ENDPOINT = URI.create("https://openapi.tossinvest.com/api/v1/orders/");
	private static final String HTTP_LOG_PROPERTY = "jdk.httpclient.HttpClient.log";
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	static final int MAX_RESPONSE_BYTES = 1_048_576;
	private final URI endpoint;
	private final Supplier<Map<String, String>> environment;
	private final HttpClient http;
	private final TossOrderMapper mapper = new TossOrderMapper();

	public TossOrderClient() {
		this(ENDPOINT, System::getenv, false);
	}

	// Test seam: literal loopback HTTP and exactly the production lookup prefix.
	TossOrderClient(URI endpoint, Supplier<Map<String, String>> environment) {
		this(endpoint, environment, true);
	}

	private TossOrderClient(URI endpoint, Supplier<Map<String, String>> environment, boolean test) {
		if (test && (endpoint == null || !"http".equals(endpoint.getScheme())
				|| !Set.of("127.0.0.1", "[::1]").contains(endpoint.getHost() == null ? "" : endpoint.getHost())
				|| !ENDPOINT.getRawPath().equals(endpoint.getRawPath()) || endpoint.getRawUserInfo() != null
				|| endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)) {
			throw new TossApiException(TossErrorCode.BAD_REQUEST);
		}
		if (environment == null) {
			throw new TossApiException(TossErrorCode.BAD_REQUEST);
		}
		this.endpoint = endpoint;
		this.environment = environment;
		checkTransportConfiguration();
		try {
			http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
					.version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
					.proxy(ProxySelector.of(null)).build();
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.NETWORK_ERROR);
		}
	}

	private static void checkTransportConfiguration() {
		try {
			List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
			// Logging may already have been cached, even after clearing the property.
			String logOption = "-D" + HTTP_LOG_PROPERTY + "=";
			if (!System.getProperty(HTTP_LOG_PROPERTY, "").isEmpty()
					|| arguments.stream().anyMatch(arg -> arg.startsWith(logOption) && arg.length() > logOption.length())) {
				throw new IllegalStateException();
			}
			for (var entry : Map.of("jdk.httpclient.disableRetryConnect", "true",
					"jdk.httpclient.redirects.retrylimit", "1").entrySet()) {
				String option = "-D" + entry.getKey();
				List<String> settings = arguments.stream()
						.filter(arg -> arg.equals(option) || arg.startsWith(option + "=")).toList();
				if (settings.isEmpty() || settings.stream().anyMatch(arg -> !arg.equals(option + "=" + entry.getValue()))
						|| !entry.getValue().equals(System.getProperty(entry.getKey()))) {
					throw new IllegalStateException();
				}
			}
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.UNSAFE_TRANSPORT_CONFIGURATION);
		}
	}

	public TossOrderSnapshot getOrder(TossOrderRequest request) {
		if (request == null) {
			throw new TossApiException(TossErrorCode.BAD_REQUEST);
		}
		// Encode before reading any environment values; reject malformed UTF-16.
		String segment = encodeSegment(request.orderId());
		String json = send(authenticatedRequest(URI.create(endpoint.toASCIIString() + segment)));
		try {
			return mapper.map(request, json);
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.INVALID_RESPONSE);
		}
	}

	/** Retrieves exactly one CLOSED page; the caller owns any subsequent page request. */
	public TossClosedOrdersSnapshot getClosedOrders(TossClosedOrdersRequest request) {
		if (request == null) {
			throw new TossApiException(TossErrorCode.BAD_REQUEST);
		}
		StringBuilder query = new StringBuilder("status=CLOSED&limit=").append(request.limit());
		appendQuery(query, "symbol", request.symbol());
		appendQuery(query, "from", request.from());
		appendQuery(query, "to", request.to());
		appendQuery(query, "cursor", request.cursor());
		// Absolute path on the already validated origin; never resolve a caller-supplied URI.
		URI uri = endpoint.resolve("/api/v1/orders?" + query);
		String json = send(authenticatedRequest(uri));
		try {
			return mapper.mapClosedOrders(request, json);
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.INVALID_RESPONSE);
		}
	}

	private static void appendQuery(StringBuilder query, String name, String value) {
		if (value != null) {
			// The same UTF-8 escaping protects opaque path segments and query values.
			query.append('&').append(name).append('=').append(encodeSegment(value));
		}
	}

	private HttpRequest authenticatedRequest(URI uri) {
		checkTransportConfiguration();
		String token;
		String account;
		try {
			Map<String, String> values = environment.get();
			token = values.get("TOSS_ACCESS_TOKEN");
			account = values.get("TOSS_ACCOUNT_SEQ");
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.MISSING_CREDENTIALS);
		}
		if (token == null || token.isEmpty() || account == null || account.isEmpty()) {
			throw new TossApiException(TossErrorCode.MISSING_CREDENTIALS);
		}
		// Project header policy, not a claim about official token format or account validity.
		if (token.length() > 8192 || !token.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)
				|| !validAccount(account)) {
			throw new TossApiException(TossErrorCode.INVALID_CREDENTIALS);
		}
		try {
			return HttpRequest.newBuilder(uri)
					.timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + token)
					.header("X-Tossinvest-Account", account).header("Accept", "application/json").GET().build();
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.INVALID_CREDENTIALS);
		}
	}

	private static boolean validAccount(String account) {
		if (account.length() > 20 || !account.matches("0|-?[1-9][0-9]*")) {
			return false;
		}
		try {
			Long.parseLong(account);
			return true;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	private static String encodeSegment(String id) {
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (Character.isLowSurrogate(c)
					|| (Character.isHighSurrogate(c) && (++i == id.length() || !Character.isLowSurrogate(id.charAt(i))))) {
				throw new TossApiException(TossErrorCode.BAD_REQUEST);
			}
		}
		StringBuilder encoded = new StringBuilder();
		for (byte value : id.getBytes(StandardCharsets.UTF_8)) {
			int c = value & 0xff;
			// Dot is encoded too, so "." and ".." cannot become URI dot segments.
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '_' || c == '~') {
				encoded.append((char) c);
			}
			else {
				encoded.append('%').append("0123456789ABCDEF".charAt(c >>> 4))
						.append("0123456789ABCDEF".charAt(c & 15));
			}
		}
		return encoded.toString();
	}

	private String send(HttpRequest request) {
		var body = new BoundedBodySubscriber();
		CompletableFuture<HttpResponse<byte[]>> response = null;
		try {
			checkTransportConfiguration();
			long started = System.nanoTime();
			response = http.sendAsync(request, info -> {
				if (info.statusCode() != 200) {
					// Classify from status only; never consume or parse an error body.
					body.reject(statusCode(info.statusCode()));
				}
				return body;
			});
			HttpResponse<byte[]> received = response.get(
					Math.max(1, REQUEST_TIMEOUT.toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
			try {
				return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(received.body())).toString();
			}
			catch (CharacterCodingException exception) {
				throw new TossApiException(TossErrorCode.INVALID_RESPONSE);
			}
		}
		catch (TimeoutException exception) {
			throw new TossApiException(TossErrorCode.TIMEOUT);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TossApiException(TossErrorCode.INTERRUPTED);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof TossApiException safe) {
				throw safe;
			}
			throw new TossApiException(cause instanceof HttpTimeoutException ? TossErrorCode.TIMEOUT : TossErrorCode.NETWORK_ERROR);
		}
		catch (TossApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new TossApiException(TossErrorCode.NETWORK_ERROR);
		}
		finally {
			body.cancel();
			if (response != null && !response.isDone()) {
				response.cancel(true);
			}
		}
	}

	private static TossErrorCode statusCode(int status) {
		if (status >= 300 && status < 400) {
			return TossErrorCode.REDIRECT_REJECTED;
		}
		if (status >= 500 && status < 600) {
			return TossErrorCode.SERVER_ERROR;
		}
		return switch (status) {
			case 400 -> TossErrorCode.BAD_REQUEST;
			case 401 -> TossErrorCode.AUTHENTICATION_FAILED;
			case 403 -> TossErrorCode.PERMISSION_DENIED;
			case 404 -> TossErrorCode.ORDER_NOT_FOUND;
			case 429 -> TossErrorCode.RATE_LIMITED;
			default -> TossErrorCode.UNEXPECTED_STATUS;
		};
	}

	@Override
	public void close() {
		http.shutdownNow();
		http.close();
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
					reject(TossErrorCode.RESPONSE_TOO_LARGE);
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable failure) {
			result.completeExceptionally(new TossApiException(failure instanceof HttpTimeoutException
					? TossErrorCode.TIMEOUT : TossErrorCode.NETWORK_ERROR));
		}

		@Override
		public synchronized void onComplete() {
			if (!cancelled) {
				result.complete(bytes.toByteArray());
			}
		}

		synchronized void reject(TossErrorCode code) {
			result.completeExceptionally(new TossApiException(code));
			cancel();
		}

		synchronized void cancel() {
			cancelled = true;
			bytes.reset();
			if (subscription != null) {
				subscription.cancel();
			}
		}
	}
}
