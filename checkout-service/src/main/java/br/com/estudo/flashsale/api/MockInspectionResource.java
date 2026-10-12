package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.ReservationService;
import br.com.estudo.flashsale.ports.StockRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.*;
@Path("/internal/mock") @Produces("application/json")
@io.quarkus.arc.profile.IfBuildProfile(anyOf = {"dev", "test"})
public class MockInspectionResource {
 @Inject StockRepository stock;
 @Inject ReservationService reservations;
 @GET @Path("/stock/{product}") public Map<String,Integer> stock(@PathParam("product") String product) {
  return Map.of("disponivel", stock.available(product));
 }
 @GET @Path("/reservations/{id}") public Map<String,Object> reservation(@PathParam("id") UUID id) {
  return reservations.result(id);
 }
}
