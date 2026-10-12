package br.com.estudo.flashsale.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderCreatedEvent(
        @JsonProperty("pedido_id") UUID pedidoId,
        @JsonProperty("produto_id") String produtoId,
        @JsonProperty("quantidade") int quantidade,
        @JsonProperty("cliente_cpf") String clienteCpf,
        @JsonProperty("cliente_nome") String clienteNome,
        @JsonProperty("data_hora_criacao") LocalDateTime dataHoraCriacao) {
}
