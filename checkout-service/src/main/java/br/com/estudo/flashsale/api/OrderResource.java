package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.CheckoutService;
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
    @Inject
    CheckoutService checkoutService;

    @POST
    public Response createOrder(CreateOrderRequest request) {
        return Response.accepted(checkoutService.createOrder(request)).build();
    }
}
