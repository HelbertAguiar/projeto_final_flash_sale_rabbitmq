package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.OrderProcessingService;
import br.com.estudo.flashsale.domain.Order;
import br.com.estudo.flashsale.ports.OrderRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
@Path("/internal/mock/orders") @Produces("application/json")
@io.quarkus.arc.profile.IfBuildProfile(anyOf = {"dev", "test"})
public class MockOrderResource {
 @Inject OrderRepository repository;
 @Inject OrderProcessingService service;
 @GET @Path("/{id}") public Order find(@PathParam("id") UUID id) {
  return repository.find(id).orElseThrow(NotFoundException::new);
 }
 @POST @Path("/{id}/retry-compensation") public Response retry(@PathParam("id") UUID id) {
  find(id);
  try { return Response.status(service.retryCompensation(id) ? 204 : 503).build(); }
  catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
 }
}
