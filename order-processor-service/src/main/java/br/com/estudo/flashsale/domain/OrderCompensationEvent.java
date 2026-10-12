package br.com.estudo.flashsale.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
public record OrderCompensationEvent(@JsonProperty("pedido_id") UUID pedidoId,
 @JsonProperty("produto_id") String produtoId, int quantidade, String motivo) {}
