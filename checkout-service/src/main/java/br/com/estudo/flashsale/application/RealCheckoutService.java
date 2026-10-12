package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.*;
import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class RealCheckoutService {
    @Inject RealDatabase database;
    @Inject RedisStockRepository stock;
    @Inject RabbitBroker broker;
    @Inject ObjectMapper mapper;

    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        CheckoutService.validate(request);
        var event = new OrderCreatedEvent(UUID.randomUUID(), request.produtoId(), request.quantidade(),
            request.clienteCpf(), request.clienteNome(), LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        database.initialize();
        try (var connection = database.connection(); var insert = connection.prepareStatement(
                "INSERT INTO checkout_outbox(pedido_id,payload,state) VALUES (?,?,'PREPARED')")) {
            insert.setObject(1, event.pedidoId()); insert.setString(2, mapper.writeValueAsString(event));
            insert.executeUpdate(); // Commit antes do Redis: intent durável para recuperação.
        } catch (Exception error) { throw new jakarta.ws.rs.ServiceUnavailableException("Banco do Checkout indisponível"); }
        try {
            if (!reservePending(event.pedidoId())) throw new InsufficientStockException(event.produtoId());
        } catch (InsufficientStockException rejected) { throw rejected; }
        catch (RuntimeException pending) {
            throw new PendingReservationException(event.pedidoId());
        }
        return new CreateOrderResponse(event.pedidoId(), "PROCESSING", event.dataHoraCriacao());
    }

    public boolean reservePending(UUID id) {
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try (var select = connection.prepareStatement("SELECT payload,state FROM checkout_outbox WHERE pedido_id=? FOR UPDATE")) {
                select.setObject(1, id);
                try (var row = select.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Pedido não encontrado");
                    String state = row.getString("state");
                    if (!state.equals("PREPARED")) { connection.commit(); return !state.equals("REJECTED"); }
                    var event = mapper.readValue(row.getString("payload"), OrderCreatedEvent.class);
                    boolean reserved = stock.reserve(event);
                    try (var update = connection.prepareStatement("UPDATE checkout_outbox SET state=? WHERE pedido_id=?")) {
                        update.setString(1, reserved ? "READY" : "REJECTED"); update.setObject(2, id); update.executeUpdate();
                    }
                    connection.commit();
                    return reserved;
                }
            }
        } catch (Exception error) { throw new IllegalStateException("Reserva pendente de reconciliação", error); }
    }

    public void dispatch() {
        List<UUID> ids = new ArrayList<>();
        try (var connection = database.connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pedido_id FROM checkout_outbox WHERE state IN ('PREPARED','READY') ORDER BY created_at LIMIT 50")) {
            while (rows.next()) ids.add(rows.getObject(1, UUID.class));
        } catch (Exception error) { throw new IllegalStateException(error); }
        for (var id : ids) {
            if (!reservePending(id)) continue;
            try (var connection = database.connection()) {
                connection.setAutoCommit(false);
                try (var select = connection.prepareStatement("SELECT payload,state FROM checkout_outbox WHERE pedido_id=? FOR UPDATE")) {
                    select.setObject(1, id);
                    try (var row = select.executeQuery()) {
                        if (row.next() && row.getString("state").equals("READY")) {
                            broker.publish("order-created", row.getString("payload").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            try (var update = connection.prepareStatement("UPDATE checkout_outbox SET state='SENT' WHERE pedido_id=?")) {
                                update.setObject(1, id); update.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception error) { throw new IllegalStateException("Publicação permanece no outbox", error); }
        }
    }

    public void compensate(byte[] body) {
        try {
            var event = mapper.readValue(body, OrderCompensationEvent.class);
            stock.compensate(event);
            try (var connection = database.connection(); var update = connection.prepareStatement(
                    "UPDATE checkout_outbox SET compensated=TRUE WHERE pedido_id=?")) {
                update.setObject(1, event.pedidoId());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Reserva não encontrada no banco");
            }
        } catch (Exception error) { throw new IllegalStateException("Compensação pendente", error); }
    }

    /** Permite acompanhar inclusive a intenção cujo POST teve resposta 503. */
    public Optional<com.fasterxml.jackson.databind.JsonNode> pending(UUID id) {
        try (var connection = database.connection(); var select = connection.prepareStatement(
                "SELECT payload,state,created_at FROM checkout_outbox WHERE pedido_id=?")) {
            select.setObject(1, id);
            try (var row = select.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var result = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(row.getString("payload"));
                boolean rejected = row.getString("state").equals("REJECTED");
                result.put("status", rejected ? "CANCELLED" : "PROCESSING");
                result.put("data_hora_atualizacao", row.getTimestamp("created_at").toLocalDateTime().toString());
                if (rejected) result.put("motivo", "Estoque insuficiente; pedido não enviado ao Processor");
                return Optional.of(result);
            }
        } catch (Exception error) { throw new jakarta.ws.rs.ServiceUnavailableException("Consulta da reserva indisponível"); }
    }

    public static class PendingReservationException extends RuntimeException {
        public final UUID pedidoId;
        public PendingReservationException(UUID pedidoId) { this.pedidoId = pedidoId; }
    }
}
