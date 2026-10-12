package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.ports.OrderEventQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.*;
@ApplicationScoped
public class InMemoryOrderEventQueue implements OrderEventQueue {
 private final BlockingQueue<OrderCreatedEvent> queue;
 @Inject public InMemoryOrderEventQueue(@ConfigProperty(name="processor.mock.queue-capacity", defaultValue="1000") int capacity) {
  queue = new ArrayBlockingQueue<>(capacity);
 }
 public boolean offer(OrderCreatedEvent event) { return queue.offer(event); }
 public OrderCreatedEvent take() throws InterruptedException { return queue.take(); }
}
