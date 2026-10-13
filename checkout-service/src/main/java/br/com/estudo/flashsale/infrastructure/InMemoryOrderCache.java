package br.com.estudo.flashsale.infrastructure;
import br.com.estudo.flashsale.ports.OrderCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@ApplicationScoped
public class InMemoryOrderCache implements OrderCache {
    private record Entry(String value, long expires) {}
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long ttl;
    public InMemoryOrderCache() { this(System::nanoTime, Duration.ofMinutes(5)); }
    public InMemoryOrderCache(LongSupplier clock, Duration ttl) { this.clock = clock; this.ttl = ttl.toNanos(); }
    public Optional<String> get(String key) {
        Entry e = entries.get(key);
        if (e == null) return Optional.empty();
        if (clock.getAsLong() >= e.expires()) { entries.remove(key, e); return Optional.empty(); }
        return Optional.of(e.value());
    }
    public void put(String key, String value) {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(e -> now >= e.getValue().expires());
        entries.put(key, new Entry(value, now + ttl));
    }
}
