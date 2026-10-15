package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.domain.*;
import br.com.estudo.flashsale.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealCheckoutServiceTest {
    RealCheckoutService service;
    Connection connection;
    PreparedStatement statement;
    ResultSet rows;
    final CreateOrderRequest request = new CreateOrderRequest("PROD-001", 2, "123", null);

    @BeforeEach void setup() throws Exception {
        service = new RealCheckoutService();
        service.database = mock(RealDatabase.class);
        service.stock = mock(RedisStockRepository.class);
        service.broker = mock(RabbitBroker.class);
        service.mapper = new ObjectMapper().findAndRegisterModules();
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(service.database.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString("state")).thenReturn("PREPARED");
        var event = new OrderCreatedEvent(UUID.randomUUID(), "PROD-001", 2, "123", null, LocalDateTime.now());
        when(rows.getString("payload")).thenReturn(service.mapper.writeValueAsString(event));
    }

    @Test void invalidInputDoesNotTouchInfrastructure() {
        assertThrows(InvalidOrderException.class, () -> service.createOrder(null));
        verifyNoInteractions(service.database, service.stock, service.broker);
    }
    @Test void savesIntentBeforeReservationAndLeavesPublicationToOutbox() throws Exception {
        when(service.stock.reserve(any())).thenReturn(true);
        var response = service.createOrder(request);
        assertEquals("PROCESSING", response.status());
        assertEquals(4, response.pedidoId().version());
        assertNotNull(response.dataHoraRequisicao());
        var sequence = inOrder(statement, service.stock);
        sequence.verify(statement).executeUpdate();
        sequence.verify(service.stock).reserve(any());
        sequence.verify(statement).setString(1, "READY");
        sequence.verify(statement).executeUpdate();
        verify(connection).commit();
        verifyNoInteractions(service.broker);
    }
    @Test void rejectedStockRecordsRejectionWithoutPublishing() throws Exception {
        assertThrows(InsufficientStockException.class, () -> service.createOrder(request));
        verify(statement).setString(1, "REJECTED");
        verify(connection).commit();
        verifyNoInteractions(service.broker);
    }
    @Test void databaseFailureDoesNotReserveStock() throws Exception {
        when(service.database.connection()).thenThrow(new SQLException("offline"));
        assertThrows(jakarta.ws.rs.ServiceUnavailableException.class, () -> service.createOrder(request));
        verifyNoInteractions(service.stock, service.broker);
    }
    @Test void ambiguousReservationRetainsIdentifierForRecovery() throws Exception {
        when(service.stock.reserve(any())).thenThrow(new IllegalStateException("Redis timeout"));
        var error = assertThrows(RealCheckoutService.PendingReservationException.class,
            () -> service.createOrder(request));
        assertNotNull(error.pedidoId);
        verify(connection, never()).commit();
        verify(service.stock, never()).compensate(any());
        verifyNoInteractions(service.broker);
    }
    @Test void reconciliationDoesNotReserveAnAlreadyReadyOrderAgain() throws Exception {
        when(rows.getString("state")).thenReturn("READY");
        assertTrue(service.reservePending(UUID.randomUUID()));
        verifyNoInteractions(service.stock, service.broker);
        verify(connection).commit();
    }
}
