package br.com.estudo.flashsale.api;
import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.ports.OrderRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
@QuarkusTest
class ProcessorIntegrationTest {
 @Inject OrderRepository repository;
 @Test void receivesAndProcessesAsynchronouslyWithJsonContract() throws Exception {
  UUID id = UUID.randomUUID();
  var body = Map.of("pedido_id", id.toString(), "produto_id", "PROD-001", "quantidade", 2,
   "cliente_cpf", "123", "data_hora_criacao", "2026-09-07T20:03:57");
  for (int i=0;i<2;i++) given().contentType("application/json").body(body)
   .post("/internal/mock/messages/order-created").then().statusCode(202);
  // Scheduled condition checks, bounded by Future.get; no fixed sleep for completion.
  try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
   var completed = new CompletableFuture<Order>();
   var task = scheduler.scheduleWithFixedDelay(() -> repository.find(id)
    .filter(o -> o.status() == OrderStatus.CONFIRMED).ifPresent(completed::complete), 0, 20, TimeUnit.MILLISECONDS);
   try { assertEquals(2, completed.get(5, TimeUnit.SECONDS).history().size()); }
   finally { task.cancel(true); }
  }
  given().get("/internal/mock/orders/"+id).then().statusCode(200)
   .body("status", equalTo("CONFIRMED")).body("produto_id", equalTo("PROD-001"))
   .body("data_hora_criacao", notNullValue()).body("data_hora_atualizacao", notNullValue());
 }
 @Test void rejectsMalformedMessages() {
  for (String body : List.of("{}", "null", "{", "{\"pedido_id\":\"not-a-uuid\"}",
    "{\"pedido_id\":\""+UUID.randomUUID()+"\",\"produto_id\":\"P\",\"quantidade\":-1,\"cliente_cpf\":\"123\",\"data_hora_criacao\":\"2026-09-07T20:03:57\"}"))
   given().contentType("application/json").body(body).post("/internal/mock/messages/order-created").then().statusCode(400);
 }
 @Test void unknownOrderReturns404() {
  given().get("/internal/mock/orders/"+UUID.randomUUID()).then().statusCode(404);
 }
}
