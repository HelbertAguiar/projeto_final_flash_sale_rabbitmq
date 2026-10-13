package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.OrderCompensationEvent;
import br.com.estudo.flashsale.ports.OrderCompensationPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import jakarta.ws.rs.WebApplicationException;
import java.util.logging.Logger;
@ApplicationScoped
public class HttpMockOrderCompensationPublisher implements OrderCompensationPublisher {
 private static final Logger LOG = Logger.getLogger(HttpMockOrderCompensationPublisher.class.getName());
 @Inject @RestClient CheckoutClient client;
 public void publish(OrderCompensationEvent event) {
  LOG.info("event=publishing transport=http queue=order-compensation pedido_id=" + event.pedidoId());
  try (var response = client.send(event)) {
   if (response.getStatus() != 204) throw new IllegalStateException("Compensação não confirmada pelo Checkout");
  } catch (WebApplicationException rejected) {
   LOG.warning("Checkout rejeitou ou falhou ao executar compensação pedido_id=" + event.pedidoId()
     + " HTTP=" + rejected.getResponse().getStatus());
   throw rejected;
  } catch (RuntimeException unavailable) {
   LOG.warning("Falha de comunicação da compensação pedido_id=" + event.pedidoId());
   throw unavailable;
  }
 }
}
