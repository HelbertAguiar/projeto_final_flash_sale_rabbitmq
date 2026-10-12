package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.ports.OrderEventQueue;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.logging.Logger;
@Path("/internal/mock/messages/order-created") @Consumes("application/json")
public class OrderMessageResource {
 @Inject OrderEventQueue queue;
 @org.eclipse.microprofile.config.inject.ConfigProperty(name="flashsale.real") boolean real;
 @POST public Response receive(OrderCreatedEvent event) {
  if (real) throw new NotFoundException();
  if (event == null || event.pedidoId() == null || event.produtoId() == null || event.produtoId().isBlank()
    || event.quantidade() <= 0 || event.clienteCpf() == null || event.clienteCpf().isBlank()
    || event.dataHoraCriacao() == null) throw new BadRequestException("Evento order-created inválido");
  Logger.getLogger(getClass().getName()).info("Evento recebido pedido_id=" + event.pedidoId());
  if (!queue.offer(event)) throw new ServiceUnavailableException("Fila simulada cheia");
  Logger.getLogger(getClass().getName()).info("Pedido colocado na fila order-created pedido_id=" + event.pedidoId());
  return Response.accepted().build();
 }
}
