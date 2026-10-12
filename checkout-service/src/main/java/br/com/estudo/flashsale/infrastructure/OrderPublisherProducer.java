package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.ports.OrderEventPublisher;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
public class OrderPublisherProducer {
 @Inject InMemoryOrderEventPublisher memory;
 @Inject @Transport HttpMockOrderEventPublisher http;
 @ConfigProperty(name="checkout.events.transport") String transport;
 @Produces OrderEventPublisher publisher() {
   return switch (transport) { case "memory" -> memory; case "http" -> http;
     default -> throw new IllegalArgumentException("checkout.events.transport deve ser memory ou http"); };
 }
}
