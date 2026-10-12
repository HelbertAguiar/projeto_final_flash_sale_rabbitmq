package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.ports.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.UUID;
import java.util.logging.Logger;
@ApplicationScoped
public class OrderProcessingService {
 private static final Logger LOG = Logger.getLogger(OrderProcessingService.class.getName());
 private final OrderRepository repository;
 private final OrderCompensationPublisher publisher;
 private final String failureMode;
 @Inject public OrderProcessingService(OrderRepository repository, OrderCompensationPublisher publisher,
   @ConfigProperty(name="processor.mock.failure-mode", defaultValue="never") String failureMode) {
  if (!failureMode.equals("never") && !failureMode.equals("always")) throw new IllegalArgumentException("failure-mode deve ser never ou always");
  this.repository = repository; this.publisher = publisher; this.failureMode = failureMode;
 }
 // Serializa também tentativas manuais para não publicar compensações simultâneas.
 public synchronized void process(OrderCreatedEvent event) {
  Order order = Order.processing(event);
  if (!repository.insert(order)) {
   LOG.info("Evento duplicado ignorado pedido_id=" + event.pedidoId()); return;
  }
  LOG.info("Início do processamento pedido_id=" + event.pedidoId());
  Order result;
  try {
   if (failureMode.equals("always")) throw new IllegalStateException("Falha simulada");
   result = order.finish(OrderStatus.CONFIRMED);
   repository.update(result);
   LOG.info("Pedido confirmado pedido_id=" + event.pedidoId());
  } catch (RuntimeException failure) {
   LOG.warning("Falha de processamento; pedido cancelado pedido_id=" + event.pedidoId());
   result = order.finish(OrderStatus.CANCELLED);
   repository.update(result);
   publishCompensation(result);
  }
 }
 public synchronized boolean retryCompensation(UUID id) {
  Order order = repository.find(id).orElseThrow(() -> new IllegalArgumentException("Pedido não encontrado"));
  if (order.status() != OrderStatus.CANCELLED) throw new IllegalArgumentException("Pedido não cancelado");
  return order.compensation().equals("COMPLETED") || publishCompensation(order);
 }
 private boolean publishCompensation(Order order) {
  try {
   publisher.publish(order.compensationEvent());
  } catch (RuntimeException failure) {
   // Não marcar como concluída: o destino pode ter recebido e perdido a resposta.
   LOG.warning("Compensação pendente; falha de entrega pedido_id=" + order.pedidoId());
   return false;
  }
  repository.update(order.compensated());
  LOG.info("Compensação publicada e concluída pedido_id=" + order.pedidoId());
  return true;
 }
}
