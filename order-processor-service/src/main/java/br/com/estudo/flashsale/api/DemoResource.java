package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.ProcessingPolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/internal/demo")
public class DemoResource {
    @ConfigProperty(name="flashsale.demo.enabled") boolean enabled;
    @Inject ProcessingPolicy policy;
    @POST @Path("/failures/{marker}")
    public Response failure(@PathParam("marker") String marker) {
        if (!enabled) throw new NotFoundException();
        if (!marker.matches("demo-failure-[a-f0-9-]{36}")) throw new BadRequestException("Marcador inválido");
        policy.failMarker(marker);
        return Response.noContent().build();
    }
}
