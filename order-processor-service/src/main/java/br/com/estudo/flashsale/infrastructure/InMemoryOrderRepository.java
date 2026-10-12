package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.Order;
import br.com.estudo.flashsale.ports.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@ApplicationScoped
public class InMemoryOrderRepository implements OrderRepository {
 private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
 public boolean insert(Order order) { return orders.putIfAbsent(order.pedidoId(), order) == null; }
 public void update(Order order) { orders.replace(order.pedidoId(), order); }
 public Optional<Order> find(UUID id) { return Optional.ofNullable(orders.get(id)); }
}
