package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.api.CreateOrderResponse;
import br.com.estudo.flashsale.domain.InsufficientStockException;
import br.com.estudo.flashsale.domain.InvalidOrderException;
import br.com.estudo.flashsale.domain.OrderCreatedEvent;
import br.com.estudo.flashsale.domain.PublicationFailedException;
import br.com.estudo.flashsale.ports.OrderEventPublisher;
import br.com.estudo.flashsale.ports.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class CheckoutService {
    private static final Logger LOG = Logger.getLogger(CheckoutService.class.getName());

    private final StockRepository stockRepository;
    private final OrderEventPublisher eventPublisher;

    @Inject
    public CheckoutService(StockRepository stockRepository, OrderEventPublisher eventPublisher) {
        this.stockRepository = stockRepository;
        this.eventPublisher = eventPublisher;
    }

    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        validate(request);

        // A decisão e o decremento ocorrem na mesma operação atômica da porta.
        if (!stockRepository.reserve(request.produtoId(), request.quantidade())) {
            throw new InsufficientStockException(request.produtoId());
        }

        UUID pedidoId = UUID.randomUUID();
        LocalDateTime requestedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OrderCreatedEvent event = new OrderCreatedEvent(pedidoId, request.produtoId(),
                request.quantidade(), request.clienteCpf(), request.clienteNome(), requestedAt);

        try {
            eventPublisher.publish(event);
        } catch (RuntimeException publicationFailure) {
            stockRepository.restore(request.produtoId(), request.quantidade());
            // A causa pode conter dados pessoais em adaptadores futuros; registrar só dados seguros.
            LOG.severe("Falha ao publicar pedido " + pedidoId + " para o produto " + request.produtoId());
            throw new PublicationFailedException(publicationFailure);
        }
        return new CreateOrderResponse(pedidoId, "PROCESSING", requestedAt);
    }

    private void validate(CreateOrderRequest request) {
        List<String> invalidFields = new ArrayList<>();
        if (request == null || request.produtoId() == null || request.produtoId().isBlank()) {
            invalidFields.add("produto_id é obrigatório");
        }
        if (request == null || request.quantidade() == null || request.quantidade() <= 0) {
            invalidFields.add("quantidade deve ser maior que zero");
        }
        if (request == null || request.clienteCpf() == null || request.clienteCpf().isBlank()) {
            invalidFields.add("cliente_cpf é obrigatório");
        }
        if (!invalidFields.isEmpty()) {
            throw new InvalidOrderException(String.join("; ", invalidFields));
        }
    }
}
