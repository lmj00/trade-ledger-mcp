package io.github.lmj.tradeledger.adapter.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.security.Key;
import java.security.Provider;
import java.security.ProviderException;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;

import javax.crypto.MacSpi;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class BinanceQuerySignerTest {
	// Public illustrative key from the official documentation, never an account credential.
	private static final String OFFICIAL_KEY =
			"NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j";
	private static final String SECRET_MARKER = "synthetic-secret-marker";
	private static final String QUERY_MARKER = "synthetic-query-marker";
	private static final String ORDER_SUFFIX = "&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1"
			+ "&price=0.1&recvWindow=5000&timestamp=1499827319559";

	@Test
	void matchesOfficialAsciiHmacVector() {
		// https://developers.binance.com/en/docs/products/spot/rest-api#hmac-keys
		assertThat(BinanceQuerySigner.sign("symbol=LTCBTC" + ORDER_SUFFIX, OFFICIAL_KEY))
				.isEqualTo("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71");
	}

	@Test
	void matchesOfficialPercentEncodedNonAsciiHmacVector() {
		String query = "symbol=%EF%BC%91%EF%BC%92%EF%BC%93%EF%BC%94%EF%BC%95%EF%BC%96" + ORDER_SUFFIX;
		assertThat(BinanceQuerySigner.sign(query, OFFICIAL_KEY))
				.isEqualTo("e1353ec6b14d888f1164ae9af8228a3dbd508bc82eb867db8ab6046442f33ef3");
	}

	@Test
	void matchesIndependentFullRequestFixtureAndReusesExactQueryBeforeSignature() {
		var request = new BinanceAccountTradesRequest("BTC&limit=1/한%", 1000);
		String query = request.toQueryString(9_007_199_254_740_993L);
		assertThat(query).isEqualTo("symbol=BTC%26limit%3D1%2F%ED%95%9C%25"
				+ "&limit=1000&recvWindow=5000&timestamp=9007199254740993");
		// Fixed independently using Node's crypto.createHmac, with the literal Base64-looking key.
		String signature = BinanceQuerySigner.sign(query, "c3ludGhldGljLWtleQ==");
		assertThat(signature).isEqualTo("f16c89421e82c9653707ecdfa2d6f12a166c89b6afe7d7dd7953e07369e3c278")
				.matches("[0-9a-f]{64}");
		String futureTransportQuery = query + "&signature=" + signature;
		assertThat(futureTransportQuery).isEqualTo("symbol=BTC%26limit%3D1%2F%ED%95%9C%25"
				+ "&limit=1000&recvWindow=5000&timestamp=9007199254740993"
				+ "&signature=f16c89421e82c9653707ecdfa2d6f12a166c89b6afe7d7dd7953e07369e3c278");
		assertThat(futureTransportQuery.substring(0, futureTransportQuery.indexOf("&signature=")))
				.isEqualTo(query);
		assertThat(request.toQueryString(9_007_199_254_740_993L)).isEqualTo(query);
	}

	@Test
	void preservesLiteralSecretWhitespaceAndPercentEscapeCasing() {
		String query = new BinanceAccountTradesRequest("BTCUSDT").toQueryString(0);
		assertThat(BinanceQuerySigner.sign(query, " synthetic-key "))
				.isEqualTo("0e2156ef09ed8d77c87d56351557f57eeb7488a3c51e9ae961ba420792e308f1");
		assertThat(BinanceQuerySigner.sign("symbol=%ed%95%9c&limit=1", "synthetic-key"))
				.isEqualTo("a3bd7e1d613fb9b138b1d93acfb4fce63b5bd766f1ac2a8457f887288015fa99");
		assertThat(BinanceQuerySigner.sign("symbol=%ED%95%9C&limit=1", "synthetic-key"))
				.isEqualTo("c95dccac3142dd4f66e853fb5f97485c65bee403322766b16d8b5e5a2aeb42da");
	}

	@Test
	void rejectsInvalidQueriesAndKeysWithFixedErrorsAndNoCauses() {
		for (String query : new String[] {null, "", " ", QUERY_MARKER + "\n", QUERY_MARKER + "\u007f",
				QUERY_MARKER + "한", QUERY_MARKER + "\uD800", QUERY_MARKER + "%",
				QUERY_MARKER + "%0", QUERY_MARKER + "%GG", QUERY_MARKER + "#fragment"}) {
			assertSafeFailure(() -> BinanceQuerySigner.sign(query, SECRET_MARKER));
		}
		for (String key : new String[] {null, "", " ", "\t\r\n", SECRET_MARKER + "\uD800",
				SECRET_MARKER + "\uDC00"}) {
			assertSafeFailure(() -> BinanceQuerySigner.sign("symbol=" + QUERY_MARKER, key));
		}
	}

	@Test
	void sanitizesCryptographicProviderFailuresIncludingNestedDiagnostics() {
		Provider failingProvider = new Provider("SyntheticBinanceFailure", "1", "Synthetic test provider") {
			{
				putService(new Service(this, "Mac", "HmacSHA256", FailingMac.class.getName(), List.of(), null) {
					@Override
					public Object newInstance(Object parameter) {
						return new FailingMac();
					}
				});
			}
		};
		try {
			assertThat(Security.insertProviderAt(failingProvider, 1)).isEqualTo(1);
			assertSafeFailure(() -> BinanceQuerySigner.sign("symbol=" + QUERY_MARKER, SECRET_MARKER));
		}
		finally {
			Security.removeProvider(failingProvider.getName());
		}
		// Ensure the provider is removed even after a failure and ordinary signing still works.
		assertThat(Security.getProvider(failingProvider.getName())).isNull();
		assertThat(BinanceQuerySigner.sign("symbol=LTCBTC" + ORDER_SUFFIX, OFFICIAL_KEY))
				.isEqualTo("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71");
	}

	@Test
	void signerIsPackageLocalAndRetainsNoFields() {
		assertThat(Modifier.isPublic(BinanceQuerySigner.class.getModifiers())).isFalse();
		assertThat(Modifier.isFinal(BinanceQuerySigner.class.getModifiers())).isTrue();
		assertThat(BinanceQuerySigner.class.getDeclaredFields()).isEmpty();
	}

	private static void assertSafeFailure(ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Unable to sign Binance query").hasNoCause().satisfies(exception -> {
					assertThat(exception.getSuppressed()).isEmpty();
					StringWriter stack = new StringWriter();
					exception.printStackTrace(new PrintWriter(stack));
					assertThat(stack.toString()).doesNotContain(SECRET_MARKER, QUERY_MARKER);
				});
	}

	private static final class FailingMac extends MacSpi {
		@Override
		protected int engineGetMacLength() {
			return 32;
		}

		@Override
		protected void engineInit(Key key, AlgorithmParameterSpec parameters) {
		}

		@Override
		protected void engineUpdate(byte value) {
		}

		@Override
		protected void engineUpdate(byte[] values, int offset, int length) {
		}

		@Override
		protected byte[] engineDoFinal() {
			var failure = new ProviderException(SECRET_MARKER, new IllegalStateException(QUERY_MARKER));
			failure.addSuppressed(new IllegalStateException(SECRET_MARKER + QUERY_MARKER));
			throw failure;
		}

		@Override
		protected void engineReset() {
		}
	}
}
