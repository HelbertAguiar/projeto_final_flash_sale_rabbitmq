package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.ports.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class InMemoryStockRepository implements StockRepository {
    private final ConcurrentHashMap<String, Integer> stock = new ConcurrentHashMap<>();

    public InMemoryStockRepository() {
        stock.put("PROD-001", 10);
        stock.put("PROD-002", 0);
    }

    @Override
    public int available(String produtoId) {
        return stock.getOrDefault(produtoId, 0);
    }

    @Override
    public boolean reserve(String produtoId, int quantidade) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva");
        }
        AtomicBoolean reserved = new AtomicBoolean(false);
        stock.computeIfPresent(produtoId, (id, current) -> {
            if (current >= quantidade) {
                reserved.set(true);
                return current - quantidade;
            }
            return current;
        });
        return reserved.get();
    }

    @Override
    public void restore(String produtoId, int quantidade) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva");
        }
        stock.computeIfPresent(produtoId, (id, current) -> Math.addExact(current, quantidade));
    }

    public void prepareDemoStock(int quantity) { stock.put("PROD-001", quantity); }

    // Isola os testes que usam o singleton CDI; não faz parte da porta de produção.
    void resetForTests() {
        stock.clear();
        stock.put("PROD-001", 10);
        stock.put("PROD-002", 0);
    }
}
