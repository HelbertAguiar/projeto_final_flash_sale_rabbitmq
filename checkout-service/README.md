# Checkout Service

MVP da seção 4.1, integrado ao Order Processor da seção 4.4. Java 21 / Quarkus / Maven, estoque e reservas em memória.

O endpoint público continua sendo `POST /v1/orders`: valida os dados, reserva atomicamente, gera UUID e publica por `OrderEventPublisher`. Por padrão utiliza transporte HTTP; nos testes utiliza `InMemoryOrderEventPublisher`. Falha na publicação devolve a reserva e retorna 503.

`POST /internal/mock/messages/order-compensation` valida a reserva original e devolve estoque uma única vez por pedido. Consultas de estoque e reserva estão disponíveis apenas em dev/test. Não há API pública de consulta de pedidos nesta etapa.

Na raiz do repositório:

```bash
mvn -f checkout-service/pom.xml quarkus:dev
mvn -f checkout-service/pom.xml test
```

Inicie também o Processor para criar pedidos com transporte HTTP. Para executar o Checkout isolado: `-Dcheckout.events.transport=memory`.

Veja o [README principal](../README.md) para arquitetura, configuração, IntelliJ, contratos, demonstrações de sucesso/falha, testes ponta a ponta e limitações de consistência e durabilidade.
