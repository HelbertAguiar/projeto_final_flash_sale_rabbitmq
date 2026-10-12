package br.com.estudo.flashsale.ports;

import br.com.estudo.flashsale.domain.Order;
import java.util.*;
public interface OrderRepository {
 boolean insert(Order order);
 void update(Order order);
 Optional<Order> find(UUID id);
}
