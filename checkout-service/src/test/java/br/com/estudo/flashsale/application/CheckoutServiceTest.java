package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.api.CreateOrderResponse;
import br.com.estudo.flashsale.domain.InsufficientStockException;
import br.com.estudo.flashsale.domain.InvalidOrderException;
import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.domain.PublicationFailedException;
import br.com.estudo.flashsale.ports.OrderEventPublisher;
import br.com.estudo.flashsale.ports.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutServiceTest {
    private StockRepository stock;
    private OrderEventPublisher publisher;
    private CheckoutService service;
    private final CreateOrderRequest request = new CreateOrderRequest("PROD-001", 2, "50329291076", "Fernando Augusto");

    @BeforeEach
    void setUp() {
        stock = mock(StockRepository.class);
        publisher = mock(OrderEventPublisher.class);
        service = new CheckoutService(stock, publisher);
    }

    @Test
    void createsOrderAndPublishesContractEvent() {
        when(stock.reserve("PROD-001", 2)).thenReturn(true);

        CreateOrderResponse response = service.createOrder(request);

        assertEquals("PROCESSING", response.status());
        assertEquals(4, response.pedidoId().version());
        assertEquals(2, response.pedidoId().variant()); // UUID IETF variant
        assertNotNull(response.dataHoraRequisicao());
        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publisher).publish(captor.capture());
        OrderCreatedEvent event = captor.getValue();
        assertEquals(response.pedidoId(), event.pedidoId());
        assertEquals(request.produtoId(), event.produtoId());
        assertEquals(request.quantidade(), event.quantidade());
        assertEquals(request.clienteCpf(), event.clienteCpf());
        assertEquals(request.clienteNome(), event.clienteNome());
        assertEquals(response.dataHoraRequisicao(), event.dataHoraCriacao());
        verify(stock, never()).restore(anyString(), anyInt());
    }

    @Test
    void insufficientStockDoesNotPublishOrRestore() {
        assertThrows(InsufficientStockException.class, () -> service.createOrder(request));
        verify(stock).reserve("PROD-001", 2);
        verifyNoInteractions(publisher);
        verify(stock, never()).restore(anyString(), anyInt());
    }

    @Test
    void publicationFailureRestoresStock() {
        when(stock.reserve("PROD-001", 2)).thenReturn(true);
        doThrow(new IllegalStateException("broker indisponível")).when(publisher).publish(any());

        assertThrows(PublicationFailedException.class, () -> service.createOrder(request));

        verify(stock).restore("PROD-001", 2);
    }

    @Test
    void validatesRequiredFieldsBeforeTouchingDependencies() {
        InvalidOrderException error = assertThrows(InvalidOrderException.class,
                () -> service.createOrder(new CreateOrderRequest(" ", 0, " ", null)));
        assertTrue(error.getMessage().contains("produto_id"));
        assertTrue(error.getMessage().contains("quantidade"));
        assertTrue(error.getMessage().contains("cliente_cpf"));
        verifyNoInteractions(stock, publisher);
    }

    @Test
    void doesNotValidateClienteNomeBeyondOriginalContract() {
        when(stock.reserve("PROD-001", 2)).thenReturn(true);
        service.createOrder(new CreateOrderRequest("PROD-001", 2, "50329291076", null));
        verify(publisher).publish(argThat(event -> event.clienteNome() == null));
    }
}
