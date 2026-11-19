package rocks.konrath.discovergy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import rocks.konrath.discovergy.client.FakeInexogyApi;

@QuarkusTest
@WithTestResource(FakeInexogyApi.class)
class LastReadingResourceTest {

	@Inject
	@ConfigProperty(name = "discovergy.base-url")
	String apiBaseUrl;

	@Test
	void passesLastReadingThrough() {
		given()
				.when().get("/last_reading/abc123")
				.then()
				.statusCode(200)
				.contentType("application/json")
				.body(is(FakeInexogyApi.LAST_READING));
	}

	@Test
	void renewsTokensWhenTheyAreRejected() throws Exception {
		given().when().get("/last_reading/abc123").then().statusCode(200);
		int loginsBefore = logins();

		fakeApi("POST", "/test/revoke");

		given().when().get("/last_reading/abc123").then().statusCode(200);
		assertEquals(loginsBefore + 1, logins());
	}

	@Test
	void passesClientErrorsThrough() {
		given()
				.when().get("/last_reading/unknown")
				.then()
				.statusCode(404);
	}

	@Test
	void rejectsInvalidMeterIds() {
		given()
				.when().get("/last_reading/abc%26x=1")
				.then()
				.statusCode(400);
	}

	@Test
	void healthEndpointIsUp() {
		given()
				.when().get("/q/health/live")
				.then()
				.statusCode(200)
				.body("status", is("UP"));
	}

	private int logins() throws Exception {
		return Integer.parseInt(fakeApi("GET", "/test/logins"));
	}

	private String fakeApi(String method, String path) throws Exception {
		URI root = URI.create(apiBaseUrl).resolve("/");
		HttpRequest request = HttpRequest.newBuilder(root.resolve(path))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
		}
	}
}
