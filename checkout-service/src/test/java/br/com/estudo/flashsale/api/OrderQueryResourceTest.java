package br.com.estudo.flashsale.api;

import br.com.estudo.flashsale.application.RealCheckoutService;
import br.com.estudo.flashsale.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.ws.rs.*;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderQueryResourceTest {
    OrderQueryResource resource;
    final UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        resource = new OrderQueryResource();
        resource.mapper = new ObjectMapper();
        resource.redisCache = mock(RedisOrderCache.class);
        resource.processor = mock(OrderQueryClient.class);
        resource.checkout = mock(RealCheckoutService.class);
    }
    @Test void confirmedMissPopulatesCacheAndHitAvoidsProcessor() throws Exception {
        var order = resource.mapper.readTree("{\"status\":\"CONFIRMED\"}");
        when(resource.processor.find(id)).thenReturn(order);
        when(resource.redisCache.get("order:" + id))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(order.toString()));
        try (var first = resource.find(id); var second = resource.find(id)) {
            assertEquals("MISS", first.getHeaderString("X-Cache"));
            assertEquals("HIT", second.getHeaderString("X-Cache"));
        }
        verify(resource.processor, times(1)).find(id);
        verify(resource.redisCache).put("order:" + id, order.toString());
    }
    @Test void transientAndCancelledOrdersAreNotCached() throws Exception {
        for (String state : new String[] {"PROCESSING", "CANCELLED"}) {
            when(resource.processor.find(id)).thenReturn(resource.mapper.readTree("{\"status\":\"" + state + "\"}"));
            try (var response = resource.find(id)) { assertEquals(200, response.getStatus()); }
        }
        verify(resource.redisCache, never()).put(anyString(), anyString());
    }
    @Test void unavailableCacheDoesNotInvalidateQuery() throws Exception {
        when(resource.redisCache.get(anyString())).thenThrow(new IllegalStateException("offline"));
        when(resource.processor.find(id)).thenReturn(resource.mapper.readTree("{\"status\":\"CONFIRMED\"}"));
        doThrow(new IllegalStateException("offline")).when(resource.redisCache).put(anyString(), anyString());
        try (var response = resource.find(id)) { assertEquals(200, response.getStatus()); }
    }
    @Test void missingProcessorOrderFallsBackToDurableCheckoutIntent() throws Exception {
        when(resource.processor.find(id)).thenThrow(new NotFoundException());
        var pending = resource.mapper.readTree("{\"status\":\"PROCESSING\"}");
        when(resource.checkout.pending(id)).thenReturn(Optional.of(pending));
        try (var response = resource.find(id)) { assertEquals(pending, response.getEntity()); }
        verify(resource.processor, times(3)).find(id);
    }
    @Test void unknownOrderAndUnavailableProcessorKeepDistinctStatuses() {
        when(resource.processor.find(id)).thenThrow(new NotFoundException());
        assertThrows(NotFoundException.class, () -> resource.find(id));
        reset(resource.processor);
        when(resource.processor.find(id)).thenThrow(new ServiceUnavailableException());
        assertThrows(ServiceUnavailableException.class, () -> resource.find(id));
    }
}
