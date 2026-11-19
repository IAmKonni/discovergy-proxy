package rocks.konrath.discovergy;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import rocks.konrath.discovergy.client.InexogyClient;
import rocks.konrath.discovergy.client.InexogyClient.ApiResponse;

/**
 * Exposes the {@code /last_reading} endpoint of the inexogy API without authorization.
 * The JSON of the API is passed through unchanged.
 */
@Path("/last_reading")
@Produces(MediaType.APPLICATION_JSON)
public class LastReadingResource {

	private static final Logger LOG = Logger.getLogger(LastReadingResource.class);
	private static final Pattern METER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	@Inject
	InexogyClient client;

	@GET
	@Path("/{meterId}")
	public Response lastReading(@PathParam("meterId") String meterId) {
		if (!METER_ID.matcher(meterId).matches()) {
			return Response.status(Response.Status.BAD_REQUEST).build();
		}
		try {
			ApiResponse response = client.get("/last_reading", Map.of("meterId", meterId));
			if (response.status() == 200) {
				return Response.ok(response.body(), MediaType.APPLICATION_JSON).build();
			}
			LOG.warnf("inexogy API answered HTTP %d for meter %s: %s", response.status(), meterId, response.body());
			// pass client errors (e.g. unknown meter, rate limit) through, everything else is a gateway error
			int status = response.status() / 100 == 4 ? response.status() : Response.Status.BAD_GATEWAY.getStatusCode();
			return Response.status(status).build();
		} catch (IOException e) {
			LOG.error("inexogy API not reachable", e);
			return Response.status(Response.Status.BAD_GATEWAY).build();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Response.serverError().build();
		}
	}
}
