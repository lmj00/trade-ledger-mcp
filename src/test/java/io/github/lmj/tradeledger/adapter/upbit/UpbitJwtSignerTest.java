package io.github.lmj.tradeledger.adapter.upbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class UpbitJwtSignerTest {

	static final String ACCESS = "fake-access-marker-\"한글";
	// Valid Base64 text deliberately distinguishes literal and decoded key material.
	static final String SECRET = "ZmFrZS1zZWNyZXQtbWFya2Vy";
	private final UpbitJwtSigner signer = new UpbitJwtSigner();

	@Test
	void independentlyVerifiesHs512ClaimsLiteralSecretAndFreshUuidNonces() throws Exception {
		String query = "uuid=123e4567-e89b-12d3-a456-426614174000";
		var nonces = new HashSet<String>();
		for (int i = 0; i < 20; i++) {
			String token = signer.sign(ACCESS, SECRET, query);
			JsonNode claims = verify(token, query);
			String nonce = claims.get("nonce").asString();
			assertThat(UUID.fromString(nonce).toString()).isEqualTo(nonce);
			assertThat(nonces.add(nonce)).isTrue();
			String[] parts = token.split("\\.");
			Mac decodedKeyMac = Mac.getInstance("HmacSHA512");
			decodedKeyMac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA512"));
			assertThat(Base64.getUrlDecoder().decode(parts[2])).isNotEqualTo(
					decodedKeyMac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
		}
	}

	@Test
	void hashesUnencodedUtf8RatherThanUrlEncodedQuery() throws Exception {
		String query = "uuid=한 글+&";
		verify(signer.sign(ACCESS, SECRET, query), query);
	}

	@Test
	void refusesMissingKeysWithoutLeakingInput() {
		for (String missing : List.of("", " ", "\t")) {
			assertMissing(missing, SECRET);
			assertMissing(ACCESS, missing);
		}
		assertMissing(null, SECRET);
		assertMissing(ACCESS, null);
	}

	private void assertMissing(String access, String secret) {
		assertThatThrownBy(() -> signer.sign(access, secret, "uuid=unused"))
				.isInstanceOfSatisfying(UpbitApiException.class, exception -> {
					assertThat(exception.code()).isEqualTo(UpbitErrorCode.MISSING_CREDENTIALS);
					assertThat(exception.toString()).doesNotContain(ACCESS, SECRET);
					assertThat(exception.getCause()).isNull();
					assertThat(exception.getSuppressed()).isEmpty();
				});
	}

	static JsonNode verify(String token, String unencodedQuery) throws Exception {
		String[] parts = token.split("\\.", -1);
		assertThat(parts).hasSize(3);
		assertThat(token).doesNotContain("=");
		JsonMapper json = JsonMapper.builder().build();
		JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
		assertThat(header.get("alg").asString()).isEqualTo("HS512");
		assertThat(header.get("typ").asString()).isEqualTo("JWT");
		JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
		assertThat(claims.get("access_key").asString()).isEqualTo(ACCESS);
		assertThat(claims.get("query_hash_alg").asString()).isEqualTo("SHA512");
		assertThat(claims.get("query_hash").asString()).isEqualTo(HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-512").digest(unencodedQuery.getBytes(StandardCharsets.UTF_8))));
		Mac mac = Mac.getInstance("HmacSHA512");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
		assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(
				mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
		return claims;
	}
}
