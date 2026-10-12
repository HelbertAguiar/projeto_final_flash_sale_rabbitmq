package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.application.CheckoutService;
import br.com.estudo.flashsale.domain.InsufficientStockException;
import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CheckoutIntegrationTest {
    @Inject InMemoryStockRepository stock;
    @Inject InMemoryOrderEventPublisher publisher;
    @Inject CheckoutService service;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void resetState() {
        stock.resetForTests();
        publisher.resetForTests();
    }

    @Test
    void createsOrderWithExactHttpAndJsonContract() throws Exception {
        String response = given().contentType("application/json")
                .body(request("PROD-001", 2, "50329291076", "Fernando Augusto"))
                .when().post("/v1/orders")
                .then().statusCode(202).contentType("application/json")
                .body("status", equalTo("PROCESSING"))
                .body("pedido_id", notNullValue())
                .body("data_hora_requisicao", notNullValue())
                .extract().asString();

        Map<?, ?> json = mapper.readValue(response, Map.class);
        assertEquals(3, json.size());
        UUID id = UUID.fromString((String) json.get("pedido_id"));
        assertEquals(4, id.version());
        assertEquals(8, stock.available("PROD-001"));
        assertEquals(1, publisher.publishedEvents().size());
        OrderCreatedEvent event = publisher.publishedEvents().getFirst();
        assertEquals(id, event.pedidoId());
        assertEquals("PROD-001", event.produtoId());
        assertEquals(2, event.quantidade());
        assertEquals("50329291076", event.clienteCpf());
        assertEquals("Fernando Augusto", event.clienteNome());
        assertEquals(json.get("data_hora_requisicao"), event.dataHoraCriacao().toString());
        Map<?, ?> eventJson = mapper.convertValue(event, Map.class);
        assertEquals(6, eventJson.size());
        assertTrue(eventJson.keySet().containsAll(List.of("pedido_id", "produto_id", "quantidade",
                "cliente_cpf", "cliente_nome", "data_hora_criacao")));
    }

    @Test
    void insufficientStockAndUnknownProductKeepStockAndDoNotPublish() {
        given().contentType("application/json").body(request("PROD-001", 11, "123", null))
                .when().post("/v1/orders").then().statusCode(409)
                .body("mensagem", equalTo("Estoque insuficiente para o produto PROD-001"));
        given().contentType("application/json").body(request("PROD-002", 1, "123", null))
                .when().post("/v1/orders").then().statusCode(409)
                .body("mensagem", equalTo("Estoque insuficiente para o produto PROD-002"));
        given().contentType("application/json").body(request("INEXISTENTE", 1, "123", null))
                .when().post("/v1/orders").then().statusCode(409)
                .body("mensagem", equalTo("Estoque insuficiente para o produto INEXISTENTE"));
        assertEquals(0, stock.available("PROD-002"));
        assertEquals(10, stock.available("PROD-001"));
        assertTrue(publisher.publishedEvents().isEmpty());
    }

    @Test
    void rejectsInvalidFieldsWithClearMessages() {
        for (Object[] invalid : new Object[][] {
                {request("PROD-001", 0, "123", null), "quantidade"},
                {request("PROD-001", -1, "123", null), "quantidade"},
                {request(null, 1, "123", null), "produto_id"},
                {request("", 1, "123", null), "produto_id"},
                {request("PROD-001", 1, null, null), "cliente_cpf"},
                {request("PROD-001", 1, "", null), "cliente_cpf"},
                {request("PROD-001", null, "123", null), "quantidade"}
        }) {
            given().contentType("application/json").body(invalid[0])
                    .when().post("/v1/orders").then().statusCode(400)
                    .body("mensagem", containsString((String) invalid[1]));
        }
        assertEquals(10, stock.available("PROD-001"));
        assertTrue(publisher.publishedEvents().isEmpty());
    }

    @Test
    void publicationFailureRestoresStockAndReturns503() {
        publisher.failNextPublication();
        given().contentType("application/json").body(request("PROD-001", 3, "123", null))
                .when().post("/v1/orders").then().statusCode(503)
                .body("mensagem", equalTo("Não foi possível publicar o pedido"));
        assertEquals(10, stock.available("PROD-001"));
        assertTrue(publisher.publishedEvents().isEmpty());
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        CreateOrderRequest order = new CreateOrderRequest("PROD-001", 1, "123", null);
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                calls.add(() -> {
                    try {
                        service.createOrder(order);
                        return true;
                    } catch (InsufficientStockException expected) {
                        return false;
                    }
                });
            }
            List<Future<Boolean>> results = executor.invokeAll(calls);
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) accepted++;
            }
            assertEquals(10, accepted);
        }
        assertEquals(0, stock.available("PROD-001"));
        assertEquals(10, publisher.publishedEvents().size());
    }

    private static Map<String, Object> request(String product, Integer quantity, String cpf, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("produto_id", product);
        body.put("quantidade", quantity);
        body.put("cliente_cpf", cpf);
        body.put("cliente_nome", name);
        return body;
    }
}
