package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.application.OrderProcessingService;
import br.com.estudo.flashsale.ports.OrderEventQueue;
import io.quarkus.runtime.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.*;
import java.util.logging.Logger;
@ApplicationScoped
public class AsyncOrderConsumer {
 @Inject OrderEventQueue queue;
 @Inject OrderProcessingService service;
 private ExecutorService executor;
 @org.eclipse.microprofile.config.inject.ConfigProperty(name="flashsale.real") boolean real;
 void start(@Observes StartupEvent event) {
  if (real) return;
  executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("order-created-consumer").factory());
  executor.submit(() -> {
   while (!Thread.currentThread().isInterrupted()) {
    try {
     var order = queue.take();
     try { service.process(order); }
     catch (RuntimeException failure) {
      Logger.getLogger(getClass().getName()).severe("Falha inesperada no consumidor pedido_id=" + order.pedidoId());
     }
    } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
   }
  });
 }
 void stop(@Observes ShutdownEvent event) {
  if (executor == null) return;
  executor.shutdownNow();
  try {
   if (!executor.awaitTermination(5, TimeUnit.SECONDS))
    Logger.getLogger(getClass().getName()).warning("Consumidor não encerrou dentro do limite");
  } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
 }
}
