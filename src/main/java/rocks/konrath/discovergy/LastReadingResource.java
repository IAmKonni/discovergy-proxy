package rocks.konrath.discovergy;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.jaxrs.PathParam;

import com.discovergy.apiclient.DiscovergyApiClient;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Verb;

@Path("/last_reading")
public class LastReadingResource {
	
	@Inject
	DiscovergyApiClient apiClient;
	
	@Inject
	Logger log;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{meterId}")
    public Response last_reading(@PathParam String meterId) {
    	  	
		try {
			OAuthRequest request = apiClient.createRequest(Verb.GET, "/last_reading");
			request.addParameter("meterId", meterId);
			String response = apiClient.executeRequest(request, 200).getBody();
			
			return Response.ok(response, MediaType.APPLICATION_JSON).build();
		} catch (InterruptedException | ExecutionException | IOException e) {
			log.error("Discovergy API Server error", e);
			return Response.serverError().build();
		}
    }
}