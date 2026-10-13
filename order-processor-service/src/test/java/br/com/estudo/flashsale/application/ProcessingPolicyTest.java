package br.com.estudo.flashsale.application;
import br.com.estudo.flashsale.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ProcessingPolicyTest {
    @Test void targetedFailureDoesNotAffectOtherOrdersAndSurvivesRedelivery() {
        var policy = new ProcessingPolicy("never");
        String marker = "demo-failure-" + UUID.randomUUID();
        var event = new OrderCreatedEvent(UUID.randomUUID(), "PROD-001", 2, "123", marker, LocalDateTime.now());
        policy.failMarker(marker);
        assertEquals(OrderStatus.CANCELLED, policy.result(event));
        assertEquals(OrderStatus.CANCELLED, policy.result(event));
        var ordinary = new OrderCreatedEvent(UUID.randomUUID(), "PROD-001", 2, "123", "Cliente", LocalDateTime.now());
        assertEquals(OrderStatus.CONFIRMED, policy.result(ordinary));
    }
}
