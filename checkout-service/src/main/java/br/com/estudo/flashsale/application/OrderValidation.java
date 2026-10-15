package br.com.estudo.flashsale.application;

import br.com.estudo.flashsale.api.CreateOrderRequest;
import br.com.estudo.flashsale.domain.InvalidOrderException;
import java.util.ArrayList;
import java.util.List;

/** Validação compartilhada pelo contrato de criação, sem acesso à infraestrutura. */
public final class OrderValidation {
    private OrderValidation() { }

    public static void validate(CreateOrderRequest request) {
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
