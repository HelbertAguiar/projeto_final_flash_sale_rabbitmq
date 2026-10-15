package br.com.estudo.flashsale.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/orders")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OrderResource {
    @Inject br.com.estudo.flashsale.application.RealCheckoutService realCheckout;

    @Inject br.com.estudo.flashsale.application.DemoMaintenance maintenance;

    @POST
    public Response createOrder(CreateOrderRequest request) {
        maintenance.requests().lock();
        try {
            return Response.accepted(realCheckout.createOrder(request)).build();
        } finally { maintenance.requests().unlock(); }
    }
}
