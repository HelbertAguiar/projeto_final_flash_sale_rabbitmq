package br.com.estudo.flashsale.infrastructure;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class InMemoryOrderCacheTest {
    @Test void missHitAndExpiryWithoutSleeping() {
        var now = new AtomicLong();
        var cache = new InMemoryOrderCache(now::get, Duration.ofSeconds(300));
        assertTrue(cache.get("order:1").isEmpty());
        cache.put("order:1", "confirmed");
        assertEquals("confirmed", cache.get("order:1").orElseThrow());
        now.set(Duration.ofSeconds(300).toNanos());
        assertTrue(cache.get("order:1").isEmpty());
    }
}
