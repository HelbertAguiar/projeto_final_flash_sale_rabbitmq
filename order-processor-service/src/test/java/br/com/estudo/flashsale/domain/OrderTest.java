package br.com.estudo.flashsale.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderTest {
    final OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), "PROD-001", 2, "123", null, LocalDateTime.now());
    @Test void confirmationPreservesHistoryAndIdentity() {
        var order = Order.processing(event).finish(OrderStatus.CONFIRMED);
        assertEquals(event.pedidoId(), order.pedidoId());
        assertEquals(List.of(OrderStatus.PROCESSING, OrderStatus.CONFIRMED), order.history());
        assertEquals("NOT_REQUIRED", order.compensation());
        assertEquals(event.dataHoraCriacao(), order.dataHoraCriacao());
    }
    @Test void cancellationPreservesCompensationContract() {
        var order = Order.processing(event).finish(OrderStatus.CANCELLED);
        assertEquals(List.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED), order.history());
        assertEquals(new OrderCompensationEvent(event.pedidoId(), "PROD-001", 2,
            "Falha no processamento do pedido"), order.compensationEvent());
        assertEquals("PENDING", order.compensation());
    }
    @Test void terminalOrderCannotTransitionAgain() {
        assertThrows(IllegalStateException.class,
            () -> Order.processing(event).finish(OrderStatus.CONFIRMED).finish(OrderStatus.CANCELLED));
    }
}
