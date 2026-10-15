package br.com.estudo.flashsale.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import java.util.logging.Logger;

@ApplicationScoped
public class RealWorker {
    @Inject RealDatabase database;
    @Inject RabbitBroker broker;
    @Inject PostgresOrderRepository service;
    private final Logger log = Logger.getLogger(getClass().getName());

    @Scheduled(every="1s", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try { database.initialize(); }
        catch (RuntimeException error) { log.warning("Banco indisponível; nova tentativa no próximo ciclo"); return; }
        // Falha no outbox não impede consumir compensações/novos pedidos.
        try { broker.consume("order-created", service::process); }
        catch (RuntimeException error) { log.warning("Consumo indisponível; nova tentativa no próximo ciclo"); }
        try { service.dispatch(); }
        catch (RuntimeException error) { log.warning("Outbox pendente; nova tentativa no próximo ciclo"); }
    }
}

