package br.com.estudo.flashsale.ports;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
public interface OrderEventQueue {
 boolean offer(OrderCreatedEvent event);
 OrderCreatedEvent take() throws InterruptedException;
}
