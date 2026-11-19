package rocks.konrath.discovergy.client;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal OAuth 1.0a (RFC 5849) request signer using HMAC-SHA1, as required by the inexogy API.
 */
public final class OAuth1Signer {

	private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

	private OAuth1Signer() {
	}

	/**
	 * Builds the value of the {@code Authorization} header for a request.
	 *
	 * @param method        HTTP method
	 * @param uri           request URI, query parameters are included in the signature
	 * @param formParams    form-urlencoded body parameters (included in the signature), may be empty
	 * @param consumer      consumer key and secret
	 * @param token         token and secret, or {@code null} before a request token exists
	 * @param extraOAuth    additional oauth_* parameters such as oauth_callback or oauth_verifier
	 */
	public static String authorizationHeader(String method, URI uri, Map<String, String> formParams,
			Credentials consumer, Credentials token, Map<String, String> extraOAuth) {
		return authorizationHeader(method, uri, formParams, consumer, token, extraOAuth,
				String.valueOf(System.currentTimeMillis() / 1000), nonce());
	}

	static String authorizationHeader(String method, URI uri, Map<String, String> formParams,
			Credentials consumer, Credentials token, Map<String, String> extraOAuth, String timestamp, String nonce) {
		Map<String, String> oauth = new TreeMap<>(extraOAuth);
		oauth.put("oauth_consumer_key", consumer.key());
		oauth.put("oauth_nonce", nonce);
		oauth.put("oauth_signature_method", "HMAC-SHA1");
		oauth.put("oauth_timestamp", timestamp);
		oauth.put("oauth_version", "1.0");
		if (token != null) {
			oauth.put("oauth_token", token.key());
		}

		String signature = signature(method, uri, formParams, oauth, consumer.secret(),
				token == null ? "" : token.secret());
		oauth.put("oauth_signature", signature);

		return "OAuth " + oauth.entrySet().stream()
				.map(e -> percentEncode(e.getKey()) + "=\"" + percentEncode(e.getValue()) + "\"")
				.collect(Collectors.joining(", "));
	}

	static String signature(String method, URI uri, Map<String, String> formParams, Map<String, String> oauthParams,
			String consumerSecret, String tokenSecret) {
		List<String[]> params = new ArrayList<>();
		parseQuery(uri.getRawQuery()).forEach((k, v) -> params.add(new String[] { k, v }));
		formParams.forEach((k, v) -> params.add(new String[] { k, v }));
		oauthParams.forEach((k, v) -> params.add(new String[] { k, v }));

		String normalized = params.stream()
				.map(p -> new String[] { percentEncode(p[0]), percentEncode(p[1]) })
				.sorted((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]))
				.map(p -> p[0] + "=" + p[1])
				.collect(Collectors.joining("&"));

		String baseString = method.toUpperCase() + "&" + percentEncode(baseUri(uri)) + "&" + percentEncode(normalized);
		String key = percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret);
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			return Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA1 not available", e);
		}
	}

	/** RFC 3986 percent encoding as required by OAuth 1.0. */
	public static String percentEncode(String value) {
		StringBuilder sb = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xFF);
			if (b >= 0 && UNRESERVED.indexOf(c) >= 0) {
				sb.append(c);
			} else {
				sb.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
			}
		}
		return sb.toString();
	}

	/** Parses {@code a=b&c=d} (query strings and OAuth token responses). */
	public static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new LinkedHashMap<>();
		if (query == null || query.isBlank()) {
			return result;
		}
		for (String pair : query.trim().split("&")) {
			int idx = pair.indexOf('=');
			String k = idx < 0 ? pair : pair.substring(0, idx);
			String v = idx < 0 ? "" : pair.substring(idx + 1);
			result.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
		}
		return result;
	}

	private static String baseUri(URI uri) {
		String scheme = uri.getScheme().toLowerCase();
		String host = uri.getHost().toLowerCase();
		int port = uri.getPort();
		boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
		return scheme + "://" + host + (defaultPort ? "" : ":" + port) + uri.getRawPath();
	}

	private static String nonce() {
		byte[] bytes = new byte[16];
		// created per call: a static SecureRandom would be baked into the native image heap
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/** Key/secret pair (consumer or token). */
	public record Credentials(String key, String secret) {
		@Override
		public String toString() {
			return "Credentials[key=" + key + ", secret=***]";
		}
	}
}
