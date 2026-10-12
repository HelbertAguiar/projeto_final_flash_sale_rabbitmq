package br.com.estudo.flashsale.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateOrderRequest(
        @JsonProperty("produto_id") String produtoId,
        @JsonProperty("quantidade") Integer quantidade,
        @JsonProperty("cliente_cpf") String clienteCpf,
        @JsonProperty("cliente_nome") String clienteNome) {
}
