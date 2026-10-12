package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.ports.OrderEventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class InMemoryOrderEventPublisher implements OrderEventPublisher {
    public static final String QUEUE_NAME = "order-created";

    private final ConcurrentLinkedQueue<OrderCreatedEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    @Override
    public void publish(OrderCreatedEvent event) {
        if (failNext.compareAndSet(true, false)) {
            throw new IllegalStateException("Falha simulada na publicação em " + QUEUE_NAME);
        }
        events.add(event);
    }

    public List<OrderCreatedEvent> publishedEvents() {
        return List.copyOf(events);
    }

    public void failNextPublication() {
        failNext.set(true);
    }

    // Isola os testes que usam o singleton CDI; não faz parte da porta de produção.
    void resetForTests() {
        events.clear();
        failNext.set(false);
    }
}
