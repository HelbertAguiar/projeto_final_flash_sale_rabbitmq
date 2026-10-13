package br.com.estudo.flashsale.infrastructure;
import br.com.estudo.flashsale.ports.OrderCache;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
@ApplicationScoped
public class RedisOrderCache implements OrderCache {
    @Inject RedisDataSource redis;
    public Optional<String> get(String key) {
        var value = redis.execute("GET", key);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
    public void put(String key, String value) { redis.execute("SET", key, value, "EX", "300"); }
}
