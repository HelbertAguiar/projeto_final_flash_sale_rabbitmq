package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.domain.Order;
import br.com.estudo.flashsale.infrastructure.PostgresOrderRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.UUID;

@Path("/internal/orders") @Produces("application/json")
public class OrderQueryResource {
    @Inject PostgresOrderRepository postgres;
    @GET @Path("/{id}")
    public Order find(@PathParam("id") UUID id) {
        return postgres.find(id).orElseThrow(NotFoundException::new);
    }
}
