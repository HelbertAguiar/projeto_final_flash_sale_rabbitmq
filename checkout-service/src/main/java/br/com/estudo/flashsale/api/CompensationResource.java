package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.ReservationService;
import br.com.estudo.flashsale.domain.OrderCompensationEvent;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
@Path("/internal/mock/messages/order-compensation")
@Consumes("application/json") @Produces("application/json")
public class CompensationResource {
 @Inject ReservationService reservations;
 @POST public Response receive(OrderCompensationEvent event) {
   reservations.compensate(event); return Response.noContent().build();
 }
}
