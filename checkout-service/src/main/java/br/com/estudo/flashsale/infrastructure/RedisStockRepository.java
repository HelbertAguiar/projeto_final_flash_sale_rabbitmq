package br.com.estudo.flashsale.infrastructure;

import br.com.estudo.flashsale.domain.*;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Estoque e registro de idempotência são modificados pelo mesmo script Redis. */
@ApplicationScoped
public class RedisStockRepository {
    @Inject RedisDataSource redis;
    private static final String RESERVE = """
        local status = redis.call('HGET', KEYS[2], 'status')
        if status then
          if redis.call('HGET', KEYS[2], 'product') ~= ARGV[1] or redis.call('HGET', KEYS[2], 'quantity') ~= ARGV[2] then return -1 end
          if status == 'REJECTED' then return 0 end
          return 1
        end
        local quantity = tonumber(ARGV[2])
        local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
        if stock < quantity then
          redis.call('HSET', KEYS[2], 'product', ARGV[1], 'quantity', ARGV[2], 'status', 'REJECTED')
          return 0
        end
        redis.call('DECRBY', KEYS[1], quantity)
        redis.call('HSET', KEYS[2], 'product', ARGV[1], 'quantity', ARGV[2], 'status', 'RESERVED')
        return 1
        """;
    private static final String RESTORE = """
        if redis.call('HGET', KEYS[2], 'product') ~= ARGV[1] or redis.call('HGET', KEYS[2], 'quantity') ~= ARGV[2] then return -1 end
        local status = redis.call('HGET', KEYS[2], 'status')
        if status == 'COMPLETED' then return 0 end
        if status ~= 'RESERVED' then return -1 end
        redis.call('INCRBY', KEYS[1], ARGV[2])
        redis.call('HSET', KEYS[2], 'status', 'COMPLETED')
        return 1
        """;

    public boolean reserve(OrderCreatedEvent event) {
        int result = redis.execute("EVAL", RESERVE, "2", "stock:" + event.produtoId(),
            "reservation:" + event.pedidoId(), event.produtoId(), Integer.toString(event.quantidade())).toInteger();
        if (result < 0) throw new InvalidOrderException("Reserva divergente");
        return result == 1;
    }

    public void compensate(OrderCompensationEvent event) {
        if (event == null || event.pedidoId() == null || event.produtoId() == null || event.quantidade() <= 0
                || event.motivo() == null || event.motivo().isBlank()) throw new InvalidOrderException("Compensação inválida");
        int result = redis.execute("EVAL", RESTORE, "2", "stock:" + event.produtoId(),
            "reservation:" + event.pedidoId(), event.produtoId(), Integer.toString(event.quantidade())).toInteger();
        if (result < 0) throw new InvalidOrderException("Compensação difere da reserva original");
        redis.execute("DEL", "order:" + event.pedidoId());
    }

    public boolean ready() {
        try { return "PONG".equals(redis.execute("PING").toString()); }
        catch (RuntimeException unavailable) { return false; }
    }
}
