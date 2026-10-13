package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

/** Resultado e intenção de compensação são confirmados na mesma transação local. */
@ApplicationScoped
public class PostgresOrderRepository {
    @Inject RealDatabase database;
    @Inject RabbitBroker broker;
    @Inject ObjectMapper mapper;
    @Inject br.com.estudo.flashsale.application.ProcessingPolicy policy;

    public void process(byte[] body) {
        try {
            var event = mapper.readValue(body, OrderCreatedEvent.class);
            if (event == null || event.pedidoId() == null || event.produtoId() == null || event.produtoId().isBlank()
                || event.quantidade() <= 0 || event.clienteCpf() == null || event.clienteCpf().isBlank()
                || event.dataHoraCriacao() == null) throw new IllegalArgumentException("Evento inválido");
            java.util.logging.Logger.getLogger(getClass().getName()).info("event=consumed queue=order-created pedido_id=" + event.pedidoId());
            var order = Order.processing(event).finish(policy.result(event));
            try (var connection = database.connection()) {
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("INSERT INTO orders(pedido_id,payload) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                    insert.setObject(1, order.pedidoId()); insert.setString(2, mapper.writeValueAsString(order));
                    boolean inserted = insert.executeUpdate() == 1;
                    if (inserted && order.status() == OrderStatus.CANCELLED) {
                        try (var outbox = connection.prepareStatement("INSERT INTO compensation_outbox(pedido_id,payload) VALUES (?,?)")) {
                            outbox.setObject(1, order.pedidoId()); outbox.setString(2, mapper.writeValueAsString(order.compensationEvent()));
                            outbox.executeUpdate();
                        }
                    }
                }
                connection.commit();
                java.util.logging.Logger.getLogger(getClass().getName()).info("event=persisted pedido_id=" + order.pedidoId() + " status=" + order.status());
            }
        } catch (Exception error) { throw new IllegalStateException("Processamento não confirmado", error); }
    }

    public Optional<Order> find(UUID id) {
        try (var connection = database.connection(); var select = connection.prepareStatement("SELECT payload FROM orders WHERE pedido_id=?")) {
            select.setObject(1, id);
            try (var row = select.executeQuery()) {
                return row.next() ? Optional.of(mapper.readValue(row.getString(1), Order.class)) : Optional.empty();
            }
        } catch (Exception error) { throw new IllegalStateException("Consulta indisponível", error); }
    }

    public void dispatch() {
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try (var select = connection.prepareStatement("SELECT pedido_id,payload FROM compensation_outbox WHERE NOT published LIMIT 50 FOR UPDATE SKIP LOCKED");
                 var rows = select.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject(1, UUID.class);
                    broker.publish("order-compensation", rows.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try (var update = connection.prepareStatement("UPDATE compensation_outbox SET published=TRUE WHERE pedido_id=?")) {
                        update.setObject(1, id); update.executeUpdate();
                    }
                    java.util.logging.Logger.getLogger(getClass().getName()).info("event=published queue=order-compensation pedido_id=" + id);
                    // Confirmação do broker não significa que o Checkout já devolveu o estoque.
                    try (var update = connection.prepareStatement("UPDATE orders SET payload=jsonb_set(payload::jsonb,'{compensacao}','\"PUBLISHED\"')::text WHERE pedido_id=?")) {
                        update.setObject(1, id); update.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (Exception error) { throw new IllegalStateException("Compensação permanece no outbox", error); }
    }
}
