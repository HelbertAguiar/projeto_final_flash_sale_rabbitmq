package br.com.estudo.flashsale.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;

public record CreateOrderResponse(
        @JsonProperty("pedido_id") UUID pedidoId,
        String status,
        @JsonProperty("data_hora_requisicao") LocalDateTime dataHoraRequisicao) {
}
