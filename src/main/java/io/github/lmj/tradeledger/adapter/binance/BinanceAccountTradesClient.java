package io.github.lmj.tradeledger.adapter.binance;

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
import java.time.Clock;
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

import io.github.lmj.tradeledger.application.model.BinanceAccountTradesSnapshot;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Spot account trade GET for one symbol, using JDK HttpClient and Jackson.
 * Reads BINANCE_API_KEY and BINANCE_SECRET_KEY from the process environment on each
 * call; the local, non-secret account alias is never transmitted. Aliases must be
 * nonblank, at most 256 UTF-16 code units, without ISO controls or unpaired surrogates.
 * The destination is fixed to https://api.binance.com/api/v3/myTrades.
 *
 * <p>Start the JVM with both {@code -Djdk.httpclient.disableRetryConnect=true} and
 * {@code -Djdk.httpclient.redirects.retrylimit=1}. Startup arguments and current
 * properties must agree; late property settings cannot prove JDK cached values.
 * {@code jdk.httpclient.HttpClient.log} must remain unset or empty for the process
 * lifetime: JDK diagnostics can expose signed URIs, authentication headers and bodies.
 * A nonempty startup or current setting is rejected before creating the transport
 * or sending a request, even if a startup setting has subsequently been cleared.
 * No redirects, retries, pagination or clock synchronization requests are made.
 * Project limits: connection 3 seconds, whole body 10 seconds, body 1 MiB.
 * Close this client to release its owned connections and workers.
 *
 * <p>Without filters the most recent fills are requested; fromId and time bounds are inclusive.
 * orderId selects one order, alone or with inclusive fromId.
 * Time bounds are optional milliseconds, cannot mix with fromId or orderId, and span at most 24 hours.
 * One result up to limit (default 500, maximum 1000) is requested. A limit-sized
 * snapshot may be truncated; this is not full history, completed trades or PnL.
 * Live accounts have not been verified.
 *
 * @see <a href="https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account">Official account trade list</a>
 * @see <a href="https://developers.binance.com/en/docs/products/spot/rest-api">Official signing, headers and HTTP status policy</a>
 * @see <a href="https://developers.binance.com/en/docs/products/spot/errors">Official error codes; -2015 does not identify a single cause</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/core/java-networking.html">JDK HTTP client retry and diagnostic logging properties</a>
 */
public final class BinanceAccountTradesClient implements AutoCloseable {
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private static final URI ENDPOINT = URI.create("https://api.binance.com/api/v3/myTrades");
	private static final String HTTP_LOG_PROPERTY = "jdk.httpclient.HttpClient.log";
	private static final JsonMapper ERRORS = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
					.maxStringLength(256).maxNameLength(256).maxNumberLength(128).build()).build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
	private final URI endpoint;
	private final Supplier<Map<String, String>> environment;
	private final Clock clock;
	private final HttpClient http;
	private final BinanceAccountTradesMapper mapper = new BinanceAccountTradesMapper();

	public BinanceAccountTradesClient() {
		this(ENDPOINT, System::getenv, Clock.systemUTC(), false);
	}

	/** Package-local seam: fake environment, fixed clock, literal loopback HTTP only. */
	BinanceAccountTradesClient(URI endpoint, Supplier<Map<String, String>> environment, Clock clock) {
		this(endpoint, environment, clock, true);
	}

	private BinanceAccountTradesClient(URI endpoint, Supplier<Map<String, String>> environment,
			Clock clock, boolean test) {
		if (test && (endpoint == null || !"http".equals(endpoint.getScheme())
				|| !Set.of("127.0.0.1", "[::1]").contains(endpoint.getHost() == null ? "" : endpoint.getHost())
				|| !ENDPOINT.getRawPath().equals(endpoint.getRawPath()) || endpoint.getRawUserInfo() != null
				|| endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)) {
			throw new BinanceApiException(BinanceErrorCode.BAD_REQUEST);
		}
		if (environment == null || clock == null) {
			throw new BinanceApiException(BinanceErrorCode.BAD_REQUEST);
		}
		this.endpoint = endpoint;
		this.environment = environment;
		this.clock = clock;
		checkTransportConfiguration();
		try {
			http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
					.version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
					.proxy(ProxySelector.of(null)).build();
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.NETWORK_ERROR);
		}
	}

	private static void checkTransportConfiguration() {
		try {
			List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
			// The JDK caches its logging setting. Clearing the current property cannot
			// establish that a nonempty startup setting was never used by the transport.
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
			throw new BinanceApiException(BinanceErrorCode.UNSAFE_TRANSPORT_CONFIGURATION);
		}
	}

	public BinanceAccountTradesSnapshot getAccountTrades(String accountAlias, BinanceAccountTradesRequest request) {
		if (request == null || accountAlias == null || accountAlias.length() > 256 || !validText(accountAlias)) {
			throw new BinanceApiException(BinanceErrorCode.BAD_REQUEST);
		}
		checkTransportConfiguration();
		String query;
		try {
			query = request.toQueryString(clock.millis());
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.BAD_REQUEST);
		}
		String apiKey;
		String secret;
		try {
			Map<String, String> values = environment.get();
			apiKey = values.get("BINANCE_API_KEY");
			secret = values.get("BINANCE_SECRET_KEY");
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.MISSING_CREDENTIALS);
		}
		if (apiKey == null || apiKey.isBlank() || secret == null || secret.isBlank()) {
			throw new BinanceApiException(BinanceErrorCode.MISSING_CREDENTIALS);
		}
		if (!apiKey.chars().allMatch(c -> c >= 0x21 && c <= 0x7e) || !validText(secret)) {
			throw new BinanceApiException(BinanceErrorCode.INVALID_CREDENTIALS);
		}
		String signature;
		try {
			signature = BinanceQuerySigner.sign(query, secret);
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.SIGNING_FAILED);
		}
		HttpRequest outgoing;
		try {
			outgoing = HttpRequest.newBuilder(URI.create(endpoint.toASCIIString() + "?" + query + "&signature=" + signature))
					.timeout(REQUEST_TIMEOUT).header("X-MBX-APIKEY", apiKey)
					.header("Accept", "application/json").GET().build();
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.INVALID_CREDENTIALS);
		}
		String json = send(outgoing);
		try {
			return mapper.map(accountAlias, request, json);
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.INVALID_RESPONSE);
		}
	}

	private static boolean validText(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isISOControl(c) || Character.isLowSurrogate(c)) {
				return false;
			}
			if (Character.isHighSurrogate(c) && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) {
				return false;
			}
		}
		return true;
	}

	private String send(HttpRequest request) {
		var body = new BoundedBodySubscriber();
		CompletableFuture<HttpResponse<byte[]>> response = null;
		try {
			checkTransportConfiguration();
			long started = System.nanoTime();
			response = http.sendAsync(request, info -> body);
			HttpResponse<byte[]> received = response.get(
					Math.max(1, REQUEST_TIMEOUT.toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
			String json;
			try {
				json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(received.body())).toString();
			}
			catch (CharacterCodingException exception) {
				if (received.statusCode() != 200) {
					throw new BinanceApiException(statusCode(received.statusCode(), ""));
				}
				throw new BinanceApiException(BinanceErrorCode.INVALID_RESPONSE);
			}
			if (received.statusCode() != 200) {
				throw new BinanceApiException(statusCode(received.statusCode(), json));
			}
			return json;
		}
		catch (TimeoutException exception) {
			throw new BinanceApiException(BinanceErrorCode.TIMEOUT);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BinanceApiException(BinanceErrorCode.INTERRUPTED);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof BinanceApiException safe) {
				throw safe;
			}
			throw new BinanceApiException(cause instanceof HttpTimeoutException
					? BinanceErrorCode.TIMEOUT : BinanceErrorCode.NETWORK_ERROR);
		}
		catch (BinanceApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new BinanceApiException(BinanceErrorCode.NETWORK_ERROR);
		}
		finally {
			body.cancel();
			if (response != null && !response.isDone()) {
				response.cancel(true);
			}
		}
	}

	private static BinanceErrorCode statusCode(int status, String json) {
		if (status >= 300 && status < 400) {
			return BinanceErrorCode.REDIRECT_REJECTED;
		}
		if (status >= 500 && status < 600) {
			return BinanceErrorCode.SERVER_ERROR;
		}
		// Security blocks, bans and throttling retain their HTTP meaning even if a
		// body contains an otherwise recognized code. Never classify from msg text.
		if (status == 400 || status == 401) {
			BinanceErrorCode known = switch (errorCode(json)) {
				case -1021 -> BinanceErrorCode.INVALID_TIMESTAMP;
				case -1022 -> BinanceErrorCode.INVALID_SIGNATURE;
				case -2014 -> BinanceErrorCode.INVALID_API_KEY_FORMAT;
				case -2015 -> BinanceErrorCode.KEY_IP_OR_PERMISSION_REJECTED;
				default -> null;
			};
			if (known != null) {
				return known;
			}
		}
		return switch (status) {
			case 400 -> BinanceErrorCode.BAD_REQUEST;
			case 401 -> BinanceErrorCode.AUTHENTICATION_FAILED;
			case 403 -> BinanceErrorCode.SECURITY_RESTRICTION;
			case 418 -> BinanceErrorCode.IP_BLOCKED;
			case 429 -> BinanceErrorCode.RATE_LIMITED;
			default -> BinanceErrorCode.UNEXPECTED_STATUS;
		};
	}

	private static int errorCode(String json) {
		try (var parser = ERRORS.createParser(json)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return 0;
			}
			int depth = 1;
			int code = 0;
			boolean codeField = false;
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (depth == 0) {
					return 0;
				}
				if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
					if (parser.getString().length() > 256) {
						return 0;
					}
				}
				if ((token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
						&& parser.getString().length() > 128) {
					return 0;
				}
				if (codeField) {
					if (token != JsonToken.VALUE_NUMBER_INT) {
						return 0;
					}
					code = Integer.parseInt(parser.getString());
				}
				codeField = depth == 1 && token == JsonToken.PROPERTY_NAME && "code".equals(parser.getString());
				if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
					if (++depth > 64) {
						return 0;
					}
				}
				else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
					depth--;
				}
			}
			return depth == 0 ? code : 0;
		}
		catch (RuntimeException exception) {
			return 0;
		}
	}

	@Override
	public void close() {
		// shutdownNow also cancels any concurrent request; close waits for termination.
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
					result.completeExceptionally(new BinanceApiException(BinanceErrorCode.RESPONSE_TOO_LARGE));
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
		public void onError(Throwable failure) {
			result.completeExceptionally(new BinanceApiException(failure instanceof HttpTimeoutException
					? BinanceErrorCode.TIMEOUT : BinanceErrorCode.NETWORK_ERROR));
		}

		@Override
		public synchronized void onComplete() {
			if (!cancelled) {
				result.complete(bytes.toByteArray());
			}
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
