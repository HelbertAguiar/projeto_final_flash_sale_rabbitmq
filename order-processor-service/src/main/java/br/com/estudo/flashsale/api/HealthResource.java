package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.infrastructure.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;

@Path("/health") @Produces("application/json")
public class HealthResource {
    @ConfigProperty(name="flashsale.real") boolean real;
    @Inject RealDatabase database;
    @Inject RabbitBroker broker;


    @GET @Path("/live")
    public Map<String,String> live() { return Map.of("status","UP"); }

    @GET @Path("/ready")
    public Response ready() {
        boolean healthy = !real || (database.ready() && broker.ready());
        return Response.status(healthy ? 200 : 503)
            .entity(Map.of("status", healthy ? "UP" : "DOWN", "mode", real ? "real" : "mock")).build();
    }
}

