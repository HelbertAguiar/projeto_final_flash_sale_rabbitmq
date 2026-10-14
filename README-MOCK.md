# Ambiente MOCK

Inicialização em Windows e Linux: [README](README.md#inicialização). Verificação manual: [roteiro](docs/TESTES.md#verificação-funcional-no-mock).

As duas aplicações Quarkus rodam em JVMs independentes. Checkout usa estoque, reservas e cache em memória; envia eventos por HTTP ao Processor, que os enfileira em memória e processa de forma assíncrona. Pedidos ficam no repositório em memória. A compensação volta por HTTP ao Checkout.

O contrato público é o mesmo (`POST /v1/orders`, `GET /v1/orders/{id}`), com `PROCESSING`, `CONFIRMED` e `CANCELLED`. O cabeçalho `X-Cache` também existe, mas representa o cache local, não Redis.

Limitações:

- Encerrar uma JVM perde seus dados; não há compartilhamento entre réplicas.
- Não existem filas RabbitMQ, publisher confirms, ack AMQP, outbox durável ou DLQ neste modo.
- Uma falha de envio HTTP pode ser ambígua: o destinatário pode ter aceitado antes do timeout.
- Compensação que falhou não tem retry agendado; enquanto os dados existirem, pode ser repetida com `POST http://localhost:8081/internal/mock/orders/{id}/retry-compensation`.
- MOCK demonstra as regras funcionais, mas os 12 requisitos com PostgreSQL, RabbitMQ e Redis devem ser apresentados no REAL.
