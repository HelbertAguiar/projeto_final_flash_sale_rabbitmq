package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.domain.Order;
import br.com.estudo.flashsale.ports.OrderRepository;
import br.com.estudo.flashsale.infrastructure.PostgresOrderRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.UUID;

@Path("/internal/orders") @Produces("application/json")
public class OrderQueryResource {
    @Inject OrderRepository memory;
    @Inject PostgresOrderRepository postgres;
    @ConfigProperty(name="flashsale.real") boolean real;
    @GET @Path("/{id}")
    public Order find(@PathParam("id") UUID id) {
        return (real ? postgres.find(id) : memory.find(id)).orElseThrow(NotFoundException::new);
    }
}
