package br.com.estudo.flashsale.application;
import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.infrastructure.InMemoryStockRepository;
import br.com.estudo.flashsale.ports.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReservationServiceTest {
 @Test void concurrentCompensationsRestoreOnlyOriginalReservationOnce() throws Exception {
  var stock = new InMemoryStockRepository(); var service = new ReservationService(stock);
  UUID id = UUID.randomUUID(); stock.reserve("PROD-001", 2);
  service.register(new OrderCreatedEvent(id,"PROD-001",2,"123",null,LocalDateTime.now()));
  assertThrows(InvalidOrderException.class, () -> service.compensate(new OrderCompensationEvent(id,"PROD-001",3,"Falha")));
  assertEquals(8,stock.available("PROD-001"));
  try (var executor=Executors.newFixedThreadPool(8)) {
   List<Callable<Void>> calls = new ArrayList<>();
   for(int i=0;i<20;i++) calls.add(() -> { service.compensate(new OrderCompensationEvent(id,"PROD-001",2,"Falha")); return null; });
   for(var future:executor.invokeAll(calls)) future.get(5,TimeUnit.SECONDS);
  }
  assertEquals(10,stock.available("PROD-001"));
  assertEquals("COMPLETED",service.result(id).get("status"));
 }
 @Test void compensationBeforePublicationReturnsThenPublicationFailsDoesNotDoubleRestore() {
  var stock=new InMemoryStockRepository(); var reservations=new ReservationService(stock);
  OrderEventPublisher publisher=event -> {
   reservations.compensate(new OrderCompensationEvent(event.pedidoId(),event.produtoId(),event.quantidade(),"Falha"));
   throw new IllegalStateException("Resposta perdida");
  };
  var checkout=new CheckoutService(stock,publisher,reservations);
  assertThrows(PublicationFailedException.class, () -> checkout.createOrder(new CreateOrderRequest("PROD-001",2,"123",null)));
  assertEquals(10,stock.available("PROD-001"));
 }
 @Test void restoreFailureDoesNotCompleteCompensationAndAllowsRetry() {
  var stock=mock(StockRepository.class); var service=new ReservationService(stock); var id=UUID.randomUUID();
  service.register(new OrderCreatedEvent(id,"P",2,"123",null,LocalDateTime.now()));
  var event=new OrderCompensationEvent(id,"P",2,"Falha");
  doThrow(new IllegalStateException()).doNothing().when(stock).restore("P",2);
  assertThrows(IllegalStateException.class, () -> service.compensate(event));
  assertEquals("RESERVED",service.result(id).get("status"));
  service.compensate(event); assertEquals("COMPLETED",service.result(id).get("status"));
 }
}
