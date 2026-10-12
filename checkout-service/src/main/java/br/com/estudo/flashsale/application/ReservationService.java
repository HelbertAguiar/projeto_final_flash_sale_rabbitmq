package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.ports.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class ReservationService {
    private final StockRepository stock;
    private final Map<UUID, Reservation> reservations = new ConcurrentHashMap<>();
    private static final Logger LOG = Logger.getLogger(ReservationService.class.getName());
    @Inject public ReservationService(StockRepository stock) { this.stock = stock; }
    public void register(OrderCreatedEvent event) {
        reservations.put(event.pedidoId(), new Reservation(event.produtoId(), event.quantidade()));
    }
    public void compensate(OrderCompensationEvent event) {
        if (event == null || event.pedidoId() == null || event.produtoId() == null
                || event.quantidade() <= 0 || event.motivo() == null || event.motivo().isBlank())
            throw new InvalidOrderException("Compensação inválida");
        restore(event.pedidoId(), event.produtoId(), event.quantidade());
    }
    public void restore(UUID id, String product, int quantity) {
        Reservation reservation = reservations.get(id);
        if (reservation == null) throw new InvalidOrderException("Reserva não encontrada");
        synchronized (reservation) {
            if (!reservation.product.equals(product) || reservation.quantity != quantity)
                throw new InvalidOrderException("Compensação difere da reserva original");
            if (!reservation.completed) {
                stock.restore(reservation.product, reservation.quantity);
                reservation.completed = true;
                LOG.info("Estoque devolvido; compensação concluída pedido_id=" + id);
            }
        }
    }
    public Map<String, Object> result(UUID id) {
        Reservation r = reservations.get(id);
        if (r == null) return Map.of("status", "UNKNOWN");
        synchronized (r) { return Map.of("status", r.completed ? "COMPLETED" : "RESERVED"); }
    }
    private static class Reservation {
        final String product; final int quantity; boolean completed;
        Reservation(String product, int quantity) { this.product = product; this.quantity = quantity; }
    }
}
