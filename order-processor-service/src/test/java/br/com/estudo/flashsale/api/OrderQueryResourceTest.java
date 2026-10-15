package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.infrastructure.PostgresOrderRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class OrderQueryResourceTest {
    static PostgresOrderRepository repository;
    @BeforeAll static void install() {
        repository = mock(PostgresOrderRepository.class);
        QuarkusMock.installMockForType(repository, PostgresOrderRepository.class);
    }
    @BeforeEach void resetMock() { reset(repository); }
    @Test void returnsPersistedOrderWithOriginalContract() {
        var id = UUID.randomUUID();
        var event = new OrderCreatedEvent(id, "PROD-001", 2, "123", null, LocalDateTime.now());
        when(repository.find(id)).thenReturn(Optional.of(Order.processing(event).finish(OrderStatus.CONFIRMED)));
        given().get("/internal/orders/" + id).then().statusCode(200)
            .body("pedido_id", is(id.toString())).body("status", is("CONFIRMED"))
            .body("historico", contains("PROCESSING", "CONFIRMED"))
            .body("data_hora_criacao", notNullValue()).body("data_hora_atualizacao", notNullValue());
    }
    @Test void unknownOrderAndMalformedIdKeepHttpErrors() {
        given().get("/internal/orders/" + UUID.randomUUID()).then().statusCode(404);
        given().get("/internal/orders/not-a-uuid").then().statusCode(404);
    }
    @Test void mockEndpointsAreAbsent() {
        given().get("/internal/mock/orders/" + UUID.randomUUID()).then().statusCode(404);
        given().contentType("application/json").body("{}").post("/internal/mock/messages/order-created")
            .then().statusCode(404);
    }
}
