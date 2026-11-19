package rocks.konrath.discovergy.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rocks.konrath.discovergy.client.OAuth1Signer.Credentials;

/**
 * Client for the inexogy (formerly Discovergy) REST API
 * (<a href="https://api.inexogy.com/docs/">https://api.inexogy.com/docs/</a>).
 * <p>
 * Handles the complete OAuth 1.0a flow (consumer token, request token, authorization with
 * e-mail/password, access token) and renews the tokens when they expire or are rejected.
 */
@ApplicationScoped
public class InexogyClient {

	private static final Logger LOG = Logger.getLogger(InexogyClient.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	@ConfigProperty(name = "discovergy.base-url", defaultValue = "https://api.inexogy.com/public/v1")
	String baseUrl;

	@ConfigProperty(name = "discovergy.email")
	String email;

	@ConfigProperty(name = "discovergy.password")
	String password;

	@ConfigProperty(name = "discovergy.clientid")
	String clientId;

	@ConfigProperty(name = "discovergy.timeout", defaultValue = "PT15S")
	Duration timeout;

	@ConfigProperty(name = "discovergy.token-lifetime", defaultValue = "PT24H")
	Duration tokenLifetime;

	@Inject
	ObjectMapper objectMapper;

	private HttpClient httpClient;
	private Session session;

	@PostConstruct
	void init() {
		httpClient = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/**
	 * Performs an authorized GET request against the API.
	 *
	 * @param endpoint path below the base URL, e.g. {@code /last_reading}
	 * @param query    query parameters
	 */
	public ApiResponse get(String endpoint, Map<String, String> query) throws IOException, InterruptedException {
		URI uri = URI.create(baseUrl + endpoint + toQuery(query));

		Session current = session();
		ApiResponse response = signedGet(uri, current);
		if (response.status() == 401 || response.status() == 403) {
			LOG.infof("API answered %d, renewing OAuth tokens", response.status());
			response = signedGet(uri, renewSession(current));
		}
		return response;
	}

	private ApiResponse signedGet(URI uri, Session s) throws IOException, InterruptedException {
		String auth = OAuth1Signer.authorizationHeader("GET", uri, Map.of(), s.consumer(), s.accessToken(), Map.of());
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("Accept", "application/json")
				.header("Authorization", auth)
				.GET()
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new ApiResponse(response.statusCode(), response.body());
	}

	private synchronized Session session() throws IOException, InterruptedException {
		if (session == null || session.isExpired(tokenLifetime)) {
			session = login();
		}
		return session;
	}

	/** Renews the session unless another thread already did so after {@code stale} was obtained. */
	private synchronized Session renewSession(Session stale) throws IOException, InterruptedException {
		if (session == stale) {
			session = login();
		}
		return session;
	}

	private Session login() throws IOException, InterruptedException {
		String client = clientId + RANDOM.nextInt(1000);

		// 1. consumer token
		String consumerJson = send(HttpRequest.newBuilder(URI.create(baseUrl + "/oauth1/consumer_token"))
				.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofString(toForm(Map.of("client", client)))), "consumer token");
		JsonNode consumerNode = objectMapper.readTree(consumerJson);
		Credentials consumer = new Credentials(consumerNode.path("key").asText(), consumerNode.path("secret").asText());

		// 2. request token
		URI requestTokenUri = URI.create(baseUrl + "/oauth1/request_token");
		Map<String, String> requestTokenResponse = OAuth1Signer.parseQuery(send(HttpRequest.newBuilder(requestTokenUri)
				.header("Authorization", OAuth1Signer.authorizationHeader("POST", requestTokenUri, Map.of(), consumer, null,
						Map.of("oauth_callback", "oob")))
				.POST(HttpRequest.BodyPublishers.noBody()), "request token"));
		Credentials requestToken = new Credentials(requestTokenResponse.get("oauth_token"),
				requestTokenResponse.get("oauth_token_secret"));

		// 3. authorize request token with the account credentials
		Map<String, String> authorizeParams = new LinkedHashMap<>();
		authorizeParams.put("oauth_token", requestToken.key());
		authorizeParams.put("email", email);
		authorizeParams.put("password", password);
		String verifier = OAuth1Signer.parseQuery(send(HttpRequest.newBuilder(
				URI.create(baseUrl + "/oauth1/authorize" + toQuery(authorizeParams))).GET(), "authorization"))
				.get("oauth_verifier");

		// 4. access token
		URI accessTokenUri = URI.create(baseUrl + "/oauth1/access_token");
		Map<String, String> accessTokenResponse = OAuth1Signer.parseQuery(send(HttpRequest.newBuilder(accessTokenUri)
				.header("Authorization", OAuth1Signer.authorizationHeader("POST", accessTokenUri, Map.of(), consumer,
						requestToken, Map.of("oauth_verifier", verifier == null ? "" : verifier)))
				.POST(HttpRequest.BodyPublishers.noBody()), "access token"));
		Credentials accessToken = new Credentials(accessTokenResponse.get("oauth_token"),
				accessTokenResponse.get("oauth_token_secret"));

		if (accessToken.key() == null || accessToken.secret() == null) {
			throw new IOException("inexogy API did not return an access token");
		}
		LOG.infof("OAuth tokens renewed (client id %s)", client);
		return new Session(consumer, accessToken, Instant.now());
	}

	private String send(HttpRequest.Builder builder, String step) throws IOException, InterruptedException {
		HttpRequest request = builder.timeout(timeout).header("Accept", "*/*").build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Requesting the " + step + " failed with HTTP " + response.statusCode());
		}
		return response.body();
	}

	private static String toForm(Map<String, String> params) {
		return params.entrySet().stream()
				.map(e -> OAuth1Signer.percentEncode(e.getKey()) + "=" + OAuth1Signer.percentEncode(e.getValue()))
				.collect(Collectors.joining("&"));
	}

	private static String toQuery(Map<String, String> params) {
		return params.isEmpty() ? "" : "?" + toForm(params);
	}

	/** Status code and raw body of an API response. */
	public record ApiResponse(int status, String body) {
	}

	private record Session(Credentials consumer, Credentials accessToken, Instant createdAt) {
		boolean isExpired(Duration lifetime) {
			return createdAt.plus(lifetime).isBefore(Instant.now());
		}
	}
}
