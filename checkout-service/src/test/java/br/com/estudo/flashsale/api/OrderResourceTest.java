package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.RealCheckoutService;
import br.com.estudo.flashsale.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class OrderResourceTest {
    static RealCheckoutService checkout;
    static final String BODY = "{\"produto_id\":\"PROD-001\",\"quantidade\":2,\"cliente_cpf\":\"123\"}";
    @BeforeAll static void install() {
        checkout = mock(RealCheckoutService.class);
        QuarkusMock.installMockForType(checkout, RealCheckoutService.class);
    }
    @BeforeEach void resetMock() { reset(checkout); }
    @Test void preservesAcceptedJsonContract() {
        var id = UUID.randomUUID();
        when(checkout.createOrder(any())).thenReturn(new CreateOrderResponse(id, "PROCESSING", LocalDateTime.of(2026, 10, 15, 12, 0)));
        given().contentType("application/json").body(BODY).post("/v1/orders").then().statusCode(202)
            .body("size()", is(3)).body("pedido_id", is(id.toString())).body("status", is("PROCESSING"))
            .body("data_hora_requisicao", notNullValue());
        verify(checkout).createOrder(new CreateOrderRequest("PROD-001", 2, "123", null));
    }
    @Test void mapsValidationAndInsufficientStock() {
        when(checkout.createOrder(any())).thenThrow(new InvalidOrderException("quantidade deve ser maior que zero"));
        given().contentType("application/json").body(BODY).post("/v1/orders").then().statusCode(400)
            .body("mensagem", containsString("quantidade"));
        reset(checkout);
        when(checkout.createOrder(any())).thenThrow(new InsufficientStockException("PROD-001"));
        given().contentType("application/json").body(BODY).post("/v1/orders").then().statusCode(409);
    }
    @Test void pendingReservationReturnsRecoverableUuid() {
        var id = UUID.randomUUID();
        when(checkout.createOrder(any())).thenThrow(new RealCheckoutService.PendingReservationException(id));
        given().contentType("application/json").body(BODY).post("/v1/orders").then().statusCode(503)
            .body("pedido_id", is(id.toString()));
    }
    @Test void mockEndpointsAreAbsent() {
        given().get("/internal/mock/stock/PROD-001").then().statusCode(404);
        given().contentType("application/json").body("{}").post("/internal/mock/messages/order-compensation")
            .then().statusCode(404);
    }
}
