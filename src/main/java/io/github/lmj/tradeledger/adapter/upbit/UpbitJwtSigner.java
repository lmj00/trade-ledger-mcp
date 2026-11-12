package io.github.lmj.tradeledger.adapter.upbit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.json.JsonMapper;

final class UpbitJwtSigner {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

	String sign(String accessKey, String secretKey, String unencodedQuery) {
		if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
			throw new UpbitApiException(UpbitErrorCode.MISSING_CREDENTIALS);
		}
		try {
			String header = encode(JSON.writeValueAsBytes(Map.of("alg", "HS512", "typ", "JWT")));
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
					.digest(unencodedQuery.getBytes(StandardCharsets.UTF_8)));
			String payload = encode(JSON.writeValueAsBytes(Map.of("access_key", accessKey,
					"nonce", UUID.randomUUID().toString(), "query_hash", hash, "query_hash_alg", "SHA512")));
			String input = header + "." + payload;
			Mac mac = Mac.getInstance("HmacSHA512");
			// Upbit secrets are literal UTF-8 keys, not Base64-encoded key material.
			mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
			return input + "." + encode(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (GeneralSecurityException | RuntimeException exception) {
			throw new UpbitApiException(UpbitErrorCode.SIGNING_FAILED);
		}
	}

	private static String encode(byte[] bytes) {
		return BASE64_URL.encodeToString(bytes);
	}
}
