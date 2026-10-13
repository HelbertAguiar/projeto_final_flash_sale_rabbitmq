package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.ports.OrderEventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
@ApplicationScoped @Transport
public class HttpMockOrderEventPublisher implements OrderEventPublisher {
 @Inject @RestClient ProcessorClient client;
 public void publish(OrderCreatedEvent event) {
  try (var response = client.send(event)) {
   if (response.getStatus() != 202) throw new IllegalStateException("order-created não aceito");
   java.util.logging.Logger.getLogger(getClass().getName()).info("event=published transport=http queue=order-created pedido_id=" + event.pedidoId());
  }
 }
}
