package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.infrastructure.*;
import br.com.estudo.flashsale.application.ReservationService;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Somente em ambiente dedicado de demonstração, sem pedidos em trânsito. */
@Path("/internal/demo") @Produces("application/json")
public class DemoResource {
    @ConfigProperty(name="flashsale.demo.enabled") boolean enabled;
    @ConfigProperty(name="flashsale.real") boolean real;
    @Inject InMemoryStockRepository memory;
    @Inject RedisDataSource redis;
    @Inject ReservationService reservations;
    @Inject RealDatabase database;
    @Inject br.com.estudo.flashsale.application.DemoMaintenance maintenance;
    @Inject @org.eclipse.microprofile.rest.client.inject.RestClient OrderQueryClient processor;
    private void guard() { if (!enabled) throw new NotFoundException(); }
    @POST @Path("/stock")
    public Map<String, Integer> prepare() {
        guard();
        maintenance.preparation().lock();
        try {
            // Reservas confirmadas já podem compor um novo saldo de demonstração.
            // Reservas em trânsito/cancelamentos pendentes não podem ser sobrescritos.
            for (UUID id : outstanding()) {
                try {
                    if (!"CONFIRMED".equals(processor.find(id).path("status").asText()))
                        throw new IllegalStateException();
                } catch (RuntimeException pending) {
                    throw new WebApplicationException("Há pedidos em trânsito; aguarde processamento/compensação", 409);
                }
            }
            if (real) redis.execute("SET", "stock:PROD-001", "10");
            else memory.prepareDemoStock(10);
            return stock();
        } finally { maintenance.preparation().unlock(); }
    }
    private Set<UUID> outstanding() {
        if (!real) return reservations.outstanding();
        Set<UUID> ids = new HashSet<>();
        try (var connection = database.connection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT pedido_id FROM checkout_outbox WHERE state <> 'REJECTED' AND NOT compensated")) {
            while (rows.next()) ids.add(rows.getObject(1, UUID.class));
            return ids;
        } catch (java.sql.SQLException error) { throw new ServiceUnavailableException(); }
    }
    @GET @Path("/stock")
    public Map<String, Integer> stock() {
        guard();
        var value = real ? redis.execute("GET", "stock:PROD-001") : null;
        return Map.of("disponivel", real ? (value == null ? 0 : value.toInteger()) : memory.available("PROD-001"));
    }
    @GET @Path("/publications/{id}")
    public Map<String, Boolean> publication(@PathParam("id") UUID id) {
        guard();
        if (!real) return Map.of("published", reservations.isPublished(id));
        try (var connection = database.connection();
             var query = connection.prepareStatement("SELECT state FROM checkout_outbox WHERE pedido_id=?")) {
            query.setObject(1, id);
            try (var row = query.executeQuery()) {
                return Map.of("published", row.next() && "SENT".equals(row.getString(1)));
            }
        } catch (java.sql.SQLException error) { throw new ServiceUnavailableException(); }
    }
    @GET @Path("/reservations/{id}")
    public Map<String, Object> reservation(@PathParam("id") UUID id) {
        guard();
        if (!real) return reservations.result(id);
        var status = redis.execute("HGET", "reservation:" + id, "status");
        return Map.of("status", status == null ? "UNKNOWN" : status.toString());
    }
}
