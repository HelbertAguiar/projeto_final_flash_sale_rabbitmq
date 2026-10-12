package br.com.estudo.flashsale.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.*;
public record Order(
 @JsonProperty("pedido_id") UUID pedidoId,
 @JsonProperty("produto_id") String produtoId, int quantidade,
 @JsonProperty("cliente_cpf") String clienteCpf,
 @JsonProperty("cliente_nome") String clienteNome, OrderStatus status,
 @JsonProperty("data_hora_criacao") LocalDateTime dataHoraCriacao,
 @JsonProperty("data_hora_atualizacao") LocalDateTime dataHoraAtualizacao,
 @JsonProperty("historico") List<OrderStatus> history,
 @JsonProperty("compensacao") String compensation) {
 public static Order processing(OrderCreatedEvent e) {
  return new Order(e.pedidoId(), e.produtoId(), e.quantidade(), e.clienteCpf(), e.clienteNome(),
   OrderStatus.PROCESSING, e.dataHoraCriacao(), LocalDateTime.now(), List.of(OrderStatus.PROCESSING), "NOT_REQUIRED");
 }
 public Order finish(OrderStatus next) {
  if (status != OrderStatus.PROCESSING || next == OrderStatus.PROCESSING) throw new IllegalStateException("Transição inválida");
  return copy(next, List.of(OrderStatus.PROCESSING, next), next == OrderStatus.CANCELLED ? "PENDING" : "NOT_REQUIRED");
 }
 public Order compensated() { return copy(status, history, "COMPLETED"); }
 private Order copy(OrderStatus state, List<OrderStatus> transitions, String result) {
  return new Order(pedidoId, produtoId, quantidade, clienteCpf, clienteNome, state,
   dataHoraCriacao, LocalDateTime.now(), transitions, result);
 }
 public OrderCompensationEvent compensationEvent() {
  return new OrderCompensationEvent(pedidoId, produtoId, quantidade, "Falha no processamento do pedido");
 }
}
