package br.com.estudo.flashsale.application;
import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.infrastructure.*;
import br.com.estudo.flashsale.ports.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderProcessingServiceTest {
 OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), "PROD-001", 2, "123", null, LocalDateTime.now());
 InMemoryOrderRepository repository = new InMemoryOrderRepository();
 OrderCompensationPublisher publisher = mock(OrderCompensationPublisher.class);
 @Test void confirmsAndRecordsTransitionWithoutCompensation() {
  new OrderProcessingService(repository, publisher, "never").process(event);
  Order order = repository.find(event.pedidoId()).orElseThrow();
  assertEquals(OrderStatus.CONFIRMED, order.status());
  assertEquals(List.of(OrderStatus.PROCESSING, OrderStatus.CONFIRMED), order.history());
  assertEquals(event.dataHoraCriacao(), order.dataHoraCriacao());
  verifyNoInteractions(publisher);
 }
 @Test void cancelsAndPublishesExactCompensation() {
  new OrderProcessingService(repository, publisher, "always").process(event);
  Order order = repository.find(event.pedidoId()).orElseThrow();
  assertEquals(List.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED), order.history());
  assertEquals("COMPLETED", order.compensation());
  verify(publisher).publish(new OrderCompensationEvent(event.pedidoId(), "PROD-001", 2, "Falha no processamento do pedido"));
 }
 @Test void communicationFailureRemainsPendingAndRetryCompletesOnlyOnce() {
  doThrow(new IllegalStateException()).doNothing().when(publisher).publish(any());
  var service = new OrderProcessingService(repository, publisher, "always");
  service.process(event);
  assertEquals("PENDING", repository.find(event.pedidoId()).orElseThrow().compensation());
  service.process(event); // duplicate is not a second order or an implicit retry
  assertTrue(service.retryCompensation(event.pedidoId()));
  assertTrue(service.retryCompensation(event.pedidoId()));
  assertEquals("COMPLETED", repository.find(event.pedidoId()).orElseThrow().compensation());
  verify(publisher, times(2)).publish(any());
 }
 @Test void concurrentDuplicateEventsPublishOnce() throws Exception {
  var service = new OrderProcessingService(repository, publisher, "always");
  try (var executor = Executors.newFixedThreadPool(8)) {
   List<Callable<Void>> calls = new ArrayList<>();
   for (int i=0; i<30; i++) calls.add(() -> { service.process(event); return null; });
   for (var future : executor.invokeAll(calls)) future.get(5, TimeUnit.SECONDS);
  }
  verify(publisher, times(1)).publish(any());
 }
 @Test void rejectsInvalidModeAndInvalidStateTransition() {
  assertThrows(IllegalArgumentException.class, () -> new OrderProcessingService(repository, publisher, "sometimes"));
  assertThrows(IllegalStateException.class, () -> Order.processing(event).finish(OrderStatus.CONFIRMED).finish(OrderStatus.CANCELLED));
 }
 @Test void boundedQueueRejectsOverflowAndPreservesEvent() throws Exception {
  var queue = new InMemoryOrderEventQueue(1);
  assertTrue(queue.offer(event)); assertFalse(queue.offer(event)); assertEquals(event, queue.take());
 }
}
