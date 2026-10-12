package br.com.estudo.flashsale.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.UUID;

@RegisterRestClient(configKey="processor")
@Path("/internal/orders") @Produces("application/json")
public interface OrderQueryClient {
    @GET @Path("/{id}") JsonNode find(@PathParam("id") UUID id);
}
