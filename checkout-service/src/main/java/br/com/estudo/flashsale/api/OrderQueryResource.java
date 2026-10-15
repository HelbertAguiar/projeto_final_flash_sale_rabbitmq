package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.infrastructure.OrderQueryClient;
import com.fasterxml.jackson.databind.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.util.UUID;

/** Cache-Aside: o acesso ao banco do Processor respeita a API do proprietário. */
@Path("/v1/orders") @Produces("application/json")
public class OrderQueryResource {
    @Inject br.com.estudo.flashsale.infrastructure.RedisOrderCache redisCache;
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(OrderQueryResource.class.getName());
    @Inject ObjectMapper mapper;
    @Inject @RestClient OrderQueryClient processor;
    @Inject br.com.estudo.flashsale.application.RealCheckoutService checkout;

    @GET @Path("/{id}")
    public jakarta.ws.rs.core.Response find(@PathParam("id") UUID id) {
        String key = "order:" + id;
        br.com.estudo.flashsale.ports.OrderCache cache = redisCache;
        try {
            var cached = cache.get(key);
            if (cached.isPresent()) {
                LOG.info("event=cache_hit pedido_id=" + id);
                return jakarta.ws.rs.core.Response.ok(mapper.readTree(cached.get())).header("X-Cache", "HIT").build();
            }
        } catch (Exception unavailable) { LOG.warning("event=cache_unavailable pedido_id=" + id); }
        LOG.info("event=cache_miss pedido_id=" + id);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var order = processor.find(id);
                // Estado transitório não deve esconder uma confirmação/cancelamento novo.
                if ("CONFIRMED".equals(order.path("status").asText())) {
                    try { cache.put(key, order.toString()); }
                    catch (RuntimeException unavailable) { /* Falha do cache não invalida a consulta. */ }
                }
                return jakarta.ws.rs.core.Response.ok(order).header("X-Cache", "MISS").build();
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
        var pending = checkout.pending(id);
        if (pending.isPresent()) return jakarta.ws.rs.core.Response.ok(pending.get()).header("X-Cache", "MISS").build();
        throw new NotFoundException("Pedido não encontrado");
    }
}
