# Order Processor Service

Java 21 / Quarkus. Consome pedidos, persiste resultado e publica compensações.

## Executar

Na raiz do repositório:

```bash
bash scripts/run-mock.sh
bash scripts/run-real-local.sh
bash scripts/run-docker.sh
```

Escolha um cenário por vez. Detalhes no [README principal](../README.md).

Somente o Processor mock: `mvn -f order-processor-service/pom.xml quarkus:dev`. Com infraestrutura real já ativa: `mvn -f order-processor-service/pom.xml quarkus:dev -Dquarkus.profile=real`.

Para falha simulada acrescente `-Dprocessor.mock.failure-mode=always`. Em Docker use `bash scripts/run.sh docker-failure`. Retorne ao normal com `bash scripts/run-docker.sh`.

## Chaveamento e configuração

- [application.properties](src/main/resources/application.properties): perfil `real`, banco, RabbitMQ e modo de falha.
- [AsyncOrderConsumer](src/main/java/br/com/estudo/flashsale/infrastructure/AsyncOrderConsumer.java): fila em memória, desativada no modo real.
- [RealWorker](src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java): consome RabbitMQ e entrega o outbox a cada segundo.
- [PostgresOrderRepository](src/main/java/br/com/estudo/flashsale/infrastructure/PostgresOrderRepository.java): grava resultado e compensação na mesma transação; UUID único impede duplicidade.
- [RabbitBroker](src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java): mensagens persistentes, publisher confirms, ack manual, três tentativas e DLQ.
- [schema.sql](src/main/resources/db/schema.sql): tabelas `orders` e `compensation_outbox`.
- [OrderQueryResource](src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java): `GET /internal/orders/{id}`, fonte da consulta do Checkout.
- [Dockerfile](Dockerfile): imagem JVM Java 21.

Porta HTTP 8081. No host: PostgreSQL localhost:5434/processor e RabbitMQ localhost:5672. Dentro do Docker: processor-db:5432 e rabbitmq:5672. Vhost `/flashsale`.

No modo mock, mensagens entram por HTTP e o repositório é volátil. No real, entradas HTTP mock retornam 404. Falha de banco causa reentrega e eventual DLQ, sem ack prematuro. Falha simulada gera CANCELLED e compensação durável. `PUBLISHED` indica que RabbitMQ recebeu a compensação; consulte a reserva Redis/Checkout para confirmar a devolução efetiva.

## Verificar

```bash
mvn -f order-processor-service/pom.xml test
curl http://localhost:8081/health/ready
```

O endpoint público de consulta é no Checkout: `GET http://localhost:8080/v1/orders/{id}`. Os endpoints de inspeção e retry manuais do mock permanecem nos perfis dev/test.
