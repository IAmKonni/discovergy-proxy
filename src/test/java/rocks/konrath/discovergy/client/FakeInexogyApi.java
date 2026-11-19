package rocks.konrath.discovergy.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Small in-process fake of the inexogy API that implements the OAuth 1.0a flow and
 * verifies every signature. Test helpers: {@code POST /test/revoke} invalidates all access
 * tokens, {@code GET /test/logins} returns the number of completed logins.
 */
public class FakeInexogyApi implements QuarkusTestResourceLifecycleManager {

	public static final String EMAIL = "user@example.com";
	public static final String PASSWORD = "p@ss word&";
	public static final String LAST_READING = "{\"time\":1643927694108,\"values\":{\"power\":564100,\"energy\":70041149000000}}";

	private static final String BASE = "/public/v1";
	private static final Credentials CONSUMER = new Credentials("consumer-key", "consumer-secret");
	private static final Credentials REQUEST_TOKEN = new Credentials("request-token", "request-secret");
	private static final String VERIFIER = "verifier-123";
	private static final String ACCESS_SECRET = "access-secret";

	private final Set<String> validAccessTokens = ConcurrentHashMap.newKeySet();
	private final AtomicInteger logins = new AtomicInteger();
	private HttpServer server;

	@Override
	public Map<String, String> start() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/", this::handle);
		server.start();
		String url = "http://127.0.0.1:" + server.getAddress().getPort();
		return Map.of(
				"discovergy.base-url", url + BASE,
				"discovergy.email", EMAIL,
				"discovergy.password", PASSWORD,
				"discovergy.clientid", "test-client");
	}

	@Override
	public void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String method = ex.getRequestMethod();
		Map<String, String> query = OAuth1Signer.parseQuery(ex.getRequestURI().getRawQuery());

		switch (method + " " + path) {
			case "POST /test/revoke" -> {
				validAccessTokens.clear();
				respond(ex, 204, "");
			}
			case "GET /test/logins" -> respond(ex, 200, String.valueOf(logins.get()));
			case "POST " + BASE + "/oauth1/consumer_token" -> {
				String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				if (!body.startsWith("client=test-client")) {
					respond(ex, 400, "missing client");
					return;
				}
				respond(ex, 200, "{\"key\":\"" + CONSUMER.key() + "\",\"secret\":\"" + CONSUMER.secret()
						+ "\",\"owner\":\"test\",\"attributes\":{},\"principal\":null}");
			}
			case "POST " + BASE + "/oauth1/request_token" -> {
				Map<String, String> oauth = verify(ex, "");
				if (oauth == null || !"oob".equals(oauth.get("oauth_callback"))) {
					respond(ex, 401, "");
					return;
				}
				respond(ex, 200, "oauth_token=" + REQUEST_TOKEN.key() + "&oauth_token_secret=" + REQUEST_TOKEN.secret()
						+ "&oauth_callback_confirmed=true");
			}
			case "GET " + BASE + "/oauth1/authorize" -> {
				if (!REQUEST_TOKEN.key().equals(query.get("oauth_token")) || !EMAIL.equals(query.get("email"))
						|| !PASSWORD.equals(query.get("password"))) {
					respond(ex, 403, "");
					return;
				}
				respond(ex, 200, "oauth_verifier=" + VERIFIER);
			}
			case "POST " + BASE + "/oauth1/access_token" -> {
				Map<String, String> oauth = verify(ex, REQUEST_TOKEN.secret());
				if (oauth == null || !REQUEST_TOKEN.key().equals(oauth.get("oauth_token"))
						|| !VERIFIER.equals(oauth.get("oauth_verifier"))) {
					respond(ex, 401, "");
					return;
				}
				String token = "access-" + logins.incrementAndGet();
				validAccessTokens.add(token);
				respond(ex, 200, "oauth_token=" + token + "&oauth_token_secret=" + ACCESS_SECRET);
			}
			case "GET " + BASE + "/last_reading" -> {
				Map<String, String> oauth = verify(ex, ACCESS_SECRET);
				if (oauth == null || !validAccessTokens.contains(oauth.get("oauth_token"))) {
					respond(ex, 401, "");
				} else if (!"abc123".equals(query.get("meterId"))) {
					respond(ex, 404, "{\"message\":\"meter not found\"}");
				} else {
					respond(ex, 200, LAST_READING);
				}
			}
			default -> respond(ex, 404, "");
		}
	}

	/** Returns the oauth parameters if the request carries a valid signature, otherwise null. */
	private static Map<String, String> verify(HttpExchange ex, String tokenSecret) {
		String header = ex.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("OAuth ")) {
			return null;
		}
		Map<String, String> oauth = new TreeMap<>();
		for (String part : header.substring(6).split(",\\s*")) {
			int idx = part.indexOf('=');
			String value = part.substring(idx + 1).replaceAll("^\"|\"$", "");
			oauth.put(decode(part.substring(0, idx)), decode(value));
		}
		String signature = oauth.remove("oauth_signature");
		if (!CONSUMER.key().equals(oauth.get("oauth_consumer_key"))) {
			return null;
		}
		URI uri = URI.create("http://127.0.0.1:" + ex.getLocalAddress().getPort() + ex.getRequestURI().getRawPath()
				+ (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
		String expected = OAuth1Signer.signature(ex.getRequestMethod(), uri, Map.of(), oauth, CONSUMER.secret(), tokenSecret);
		return expected.equals(signature) ? new HashMap<>(oauth) : null;
	}

	private static String decode(String s) {
		return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = ex.getResponseBody()) {
				out.write(bytes);
			}
		}
		ex.close();
	}
}
