package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.infrastructure.OrderQueryClient;
import com.fasterxml.jackson.databind.*;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.util.UUID;

/** Cache-Aside: o acesso ao banco do Processor respeita a API do proprietário. */
@Path("/v1/orders") @Produces("application/json")
public class OrderQueryResource {
    @ConfigProperty(name="flashsale.real") boolean real;
    @Inject RedisDataSource redis;
    @Inject ObjectMapper mapper;
    @Inject @RestClient OrderQueryClient processor;
    @Inject br.com.estudo.flashsale.application.RealCheckoutService checkout;

    @GET @Path("/{id}")
    public JsonNode find(@PathParam("id") UUID id) {
        String key = "order:" + id;
        if (real) {
            try {
                var cached = redis.execute("GET", key);
                if (cached != null) return mapper.readTree(cached.toString());
            } catch (Exception unavailable) { /* Cache opcional; consultar a fonte. */ }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var order = processor.find(id);
                // Estado transitório não deve esconder uma confirmação/cancelamento novo.
                if (real && "CONFIRMED".equals(order.path("status").asText())) {
                    try { redis.execute("SET", key, order.toString(), "EX", "300"); }
                    catch (RuntimeException unavailable) { /* Falha do cache não invalida a consulta. */ }
                }
                return order;
            } catch (WebApplicationException missing) {
                if (missing.getResponse().getStatus() != 404) {
                    throw new ServiceUnavailableException("Consulta ao Processor indisponível");
                }
                if (attempt < 2) {
                    try { Thread.sleep(100); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new ServiceUnavailableException(); }
                }
            } catch (RuntimeException unavailable) {
                throw new ServiceUnavailableException("Consulta ao Processor indisponível");
            }
        }
        if (real) {
            var pending = checkout.pending(id);
            if (pending.isPresent()) return pending.get();
        }
        throw new NotFoundException("Pedido não encontrado");
    }
}
