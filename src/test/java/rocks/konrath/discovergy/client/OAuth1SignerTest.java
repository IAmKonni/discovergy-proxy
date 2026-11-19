package rocks.konrath.discovergy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import rocks.konrath.discovergy.client.OAuth1Signer.Credentials;

class OAuth1SignerTest {

	/** Reference example from the Twitter/X "Creating a signature" documentation. */
	@Test
	void producesReferenceSignature() {
		Map<String, String> oauth = new TreeMap<>(Map.of(
				"oauth_consumer_key", "xvz1evFS4wEEPTGEFPHBog",
				"oauth_nonce", "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
				"oauth_signature_method", "HMAC-SHA1",
				"oauth_timestamp", "1318622958",
				"oauth_token", "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
				"oauth_version", "1.0"));

		String signature = OAuth1Signer.signature("POST",
				URI.create("https://api.twitter.com/1.1/statuses/update.json?include_entities=true"),
				Map.of("status", "Hello Ladies + Gentlemen, a signed OAuth request!"),
				oauth,
				"kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
				"LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE");

		assertEquals("hCtSmYh+iHYCEqBWrE7C7hYmtUk=", signature);
	}

	@Test
	void buildsAuthorizationHeader() {
		String header = OAuth1Signer.authorizationHeader("GET", URI.create("https://example.com/a?b=c"), Map.of(),
				new Credentials("ck", "cs"), new Credentials("tk", "ts"), Map.of(), "1", "n");

		assertTrue(header.startsWith("OAuth "));
		assertTrue(header.contains("oauth_consumer_key=\"ck\""));
		assertTrue(header.contains("oauth_token=\"tk\""));
		assertTrue(header.contains("oauth_signature=\""));
	}

	@Test
	void percentEncodesAccordingToRfc3986() {
		assertEquals("Ladies%20%2B%20Gentlemen%21~", OAuth1Signer.percentEncode("Ladies + Gentlemen!~"));
		assertEquals("%C3%A4", OAuth1Signer.percentEncode("ä"));
	}

	@Test
	void parsesTokenResponses() {
		assertEquals(Map.of("oauth_token", "abc", "oauth_token_secret", "d+e", "oauth_callback_confirmed", "true"),
				OAuth1Signer.parseQuery("oauth_token=abc&oauth_token_secret=d%2Be&oauth_callback_confirmed=true"));
		assertTrue(OAuth1Signer.parseQuery("").isEmpty());
	}
}
