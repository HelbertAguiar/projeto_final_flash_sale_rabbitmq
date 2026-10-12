package br.com.estudo.flashsale.ports;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;

public interface OrderEventPublisher {
    void publish(OrderCreatedEvent event);
}
