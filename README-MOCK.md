# Ambiente MOCK

Esta branch preserva estoque, reservas, cache, fila e pedidos em memória, comunicação HTTP entre duas JVMs e compensação idempotente. [Execução e testes](README.md); [demonstração manual](docs/TESTES.md).

## Funcionalidades preservadas

- Validação da compra e reserva atômica sem overselling.
- Publicação HTTP ou publicador em memória para testes isolados.
- Fila limitada, consumo assíncrono e deduplicação por UUID.
- Confirmação, cancelamento controlado e histórico do pedido.
- Compensação HTTP, devolução idempotente e retry manual.
- Consulta pública, cache de confirmados com TTL de 300 s e inspeção interna.
- Testes Mockito, fixtures PROD-001=10 / PROD-002=0 e proxy HTTP de falhas no teste ponta a ponta.

## Limitações

Encerrar uma JVM perde seus dados; não há compartilhamento entre réplicas. Não existem filas RabbitMQ, publisher confirms, ack AMQP, outbox durável ou DLQ no modo mockado.

Uma falha de envio HTTP pode ser ambígua: o destinatário pode ter aceitado antes do timeout. Compensação que falhou não tem retry agendado; enquanto os dados existirem, pode ser repetida com `POST http://localhost:8081/internal/mock/orders/{id}/retry-compensation`.

MOCK demonstra regras funcionais e não comprova requisitos de infraestrutura real. Os controles internos são destinados a ambiente didático local.
