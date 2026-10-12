package br.com.estudo.flashsale.infrastructure;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest
class CompensationIntegrationTest {
 @Inject InMemoryStockRepository stock;
 @BeforeEach void reset() { stock.resetForTests(); }
 @Test void receivesCompensationAndDuplicateWithoutInflatingStock() {
  String id=given().contentType("application/json").body(Map.of("produto_id","PROD-001","quantidade",2,"cliente_cpf","123"))
   .post("/v1/orders").then().statusCode(202).extract().path("pedido_id");
  assertEquals(8,stock.available("PROD-001"));
  var body=Map.of("pedido_id",id,"produto_id","PROD-001","quantidade",2,"motivo","Falha no processamento do pedido");
  for(int i=0;i<2;i++) given().contentType("application/json").body(body)
   .post("/internal/mock/messages/order-compensation").then().statusCode(204);
  assertEquals(10,stock.available("PROD-001"));
 }
 @Test void malformedUuidAndUnknownRouteKeepHttpErrorStatus() {
  given().contentType("application/json").body("{\"pedido_id\":\"invalid\"}")
   .post("/internal/mock/messages/order-compensation").then().statusCode(400);
  given().get("/internal/mock/not-found").then().statusCode(404);
 }
 @Test void rejectsUnknownAndMalformedCompensations() {
  for(Object body:List.of(Map.of(),Map.of("pedido_id",UUID.randomUUID().toString(),"produto_id","PROD-001","quantidade",2,"motivo","Falha")))
   given().contentType("application/json").body(body).post("/internal/mock/messages/order-compensation").then().statusCode(400);
  assertEquals(10,stock.available("PROD-001"));
 }
}
