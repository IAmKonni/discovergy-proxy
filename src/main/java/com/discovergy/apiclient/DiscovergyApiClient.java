package com.discovergy.apiclient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.github.scribejava.core.model.OAuth1RequestToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth10aService;
import com.github.scribejava.core.utils.StreamUtils;

import flexjson.JSONDeserializer;

/**
 * Client for the Discovergy API (<a href="https://api.discovergy.com/docs/">https://api.discovergy.com/docs/</a>)
 */
@Singleton
public class DiscovergyApiClient {

	@Inject
	DiscovergyApi api;
	
	@Inject
	Logger log;
	
	@Inject
	@ConfigProperty(name = "discovergy.email")
	String email;
	@Inject
	@ConfigProperty(name = "discovergy.password")
	String password;
	@Inject
	@ConfigProperty(name = "discovergy.clientid")
	String clientId;
	

	private OAuth10aService authenticationService;
	private OAuth1AccessToken accessToken;
	
	private LocalDateTime renewalTime;

	private final JSONDeserializer<Map<String, String>> deserializer = new JSONDeserializer<>();

	public DiscovergyApiClient() throws InterruptedException, ExecutionException, IOException {
		email = ConfigProvider.getConfig().getValue("discovergy.email", String.class);
		password = ConfigProvider.getConfig().getValue("discovergy.password", String.class);
		clientId = ConfigProvider.getConfig().getValue("discovergy.clientid", String.class);
	}

	private void renewTokens() throws IOException, InterruptedException, ExecutionException {
		Map<String, String> consumerTokenEntries = getConsumerToken();
		authenticationService = new ServiceBuilder(consumerTokenEntries.get("key")).apiSecret(consumerTokenEntries.get("secret")).build(api);
		OAuth1RequestToken requestToken = authenticationService.getRequestToken();
		String authorizationURL = authenticationService.getAuthorizationUrl(requestToken);
		String verifier = authorize(authorizationURL);
		accessToken = authenticationService.getAccessToken(requestToken, verifier);
		renewalTime = LocalDateTime.now();
		
		log.info("OAuth tokens renewed.");
	}

	public DiscovergyApi getApi() {
		return api;
	}

	public OAuthRequest createRequest(Verb verb, String endpoint) throws InterruptedException, ExecutionException, IOException {
		return new OAuthRequest(verb, api.getBaseAddress() + endpoint);
	}

	public Response executeRequest(OAuthRequest request) throws InterruptedException, ExecutionException, IOException {
		if (isTokenExpired()) {
			renewTokens();
		}
		authenticationService.signRequest(accessToken, request);
		return authenticationService.execute(request);
	}

	/**
	 * @return
	 */
	private boolean isTokenExpired() {
		if (renewalTime == null) {
			return true;
		}
		LocalDateTime now = LocalDateTime.now();
		long hoursSinceRenewal = renewalTime.until(now, ChronoUnit.HOURS);
		return hoursSinceRenewal > 24;
	}

	public Response executeRequest(OAuthRequest request, int expectedStatusCode) throws InterruptedException, ExecutionException, IOException {
		Response response = executeRequest(request);
		if (response.getCode() != expectedStatusCode) {
			response.getBody();
			throw new RuntimeException("Status code is not " + expectedStatusCode + ": " + response);
		}
		return response;
	}

	private Map<String, String> getConsumerToken() throws IOException {
		byte[] rawRequest = ("client=" + clientId).getBytes(StandardCharsets.UTF_8);
		HttpURLConnection connection = getConnection(api.getBaseAddress() + "/oauth1/consumer_token", "POST", true, true);
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
		connection.setRequestProperty("Content-Length", Integer.toString(rawRequest.length));
		connection.connect();
		connection.getOutputStream().write(rawRequest);
		connection.getOutputStream().flush();
		String content = StreamUtils.getStreamContents(connection.getInputStream());
		connection.disconnect();
		return deserializer.deserialize(content);
	}

	private static String authorize(String authorizationURL) throws IOException {
		HttpURLConnection connection = getConnection(authorizationURL, "GET", true, false);
		connection.connect();
		String content = StreamUtils.getStreamContents(connection.getInputStream());
		connection.disconnect();
		return content.substring(content.indexOf('=') + 1);
	}

	private static HttpURLConnection getConnection(String rawURL, String method, boolean doInput, boolean doOutput) throws IOException {
		URL url = new URL(rawURL);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setDoInput(doInput);
		connection.setDoOutput(doOutput);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Accept", "*");
		connection.setInstanceFollowRedirects(false);
		connection.setRequestProperty("charset", "utf-8");
		connection.setUseCaches(false);
		return connection;
	}
}
