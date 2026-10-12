package br.com.estudo.flashsale.api;
import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.ports.OrderEventQueue;
import org.junit.jupiter.api.Test;
import jakarta.ws.rs.ServiceUnavailableException;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderMessageResourceTest {
 @Test void acceptsIntoQueueWithoutCallingProcessorAndRejectsOverflow() {
  var queue = mock(OrderEventQueue.class);
  var resource = new OrderMessageResource(); resource.queue = queue;
  var event = new OrderCreatedEvent(UUID.randomUUID(), "P", 1, "123", null, LocalDateTime.now());
  when(queue.offer(event)).thenReturn(true, false);
  assertEquals(202, resource.receive(event).getStatus());
  verify(queue).offer(event);
  assertThrows(ServiceUnavailableException.class, () -> resource.receive(event));
 }
}
