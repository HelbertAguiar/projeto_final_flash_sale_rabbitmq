# Order Processor Service

Java 21 / Quarkus. Consome RabbitMQ, persiste resultados no PostgreSQL e publica compensações duráveis.

## Executar

Na raiz, escolha uma opção:
```bash
bash scripts/start.sh real
bash scripts/run-real-local.sh
```
Para desenvolver só este módulo, inicie `bash scripts/run-infrastructure.sh` e execute `mvn -f order-processor-service/pom.xml quarkus:dev -Dquarkus.profile=real`. O perfil padrão também usa infraestrutura real. Veja [README principal](../README.md).

## Componentes

- [RealWorker](src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java): consome RabbitMQ e entrega o outbox a cada segundo.
- [PostgresOrderRepository](src/main/java/br/com/estudo/flashsale/infrastructure/PostgresOrderRepository.java): grava resultado e compensação na mesma transação; UUID único impede duplicidade.
- [RabbitBroker](src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java): mensagens persistentes, publisher confirms, ack manual, três tentativas e DLQ.
- [ProcessingPolicy](src/main/java/br/com/estudo/flashsale/application/ProcessingPolicy.java): decisão CONFIRMED/CANCELLED e falhas controladas de demonstração.
- [schema.sql](src/main/resources/db/schema.sql): orders e compensation_outbox.
- [OrderQueryResource](src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java): GET /internal/orders/{id}, consultado pelo Checkout.

HTTP 8081; PostgreSQL localhost:5434/processor; RabbitMQ localhost:5672, vhost /flashsale. Dentro do Docker: processor-db:5432 e rabbitmq:5672.

Falha de banco causa reentrega e eventual DLQ, sem ack prematuro. Cancelamento controlado gera compensação durável. PUBLISHED indica que RabbitMQ recebeu a compensação; consulte a reserva Redis/Checkout para confirmar a devolução.

Para cancelar todas as compras: `-Dprocessor.failure-mode=always` ou `bash scripts/run.sh docker-failure`. O alias antigo `processor.mock.failure-mode` continua aceito se a configuração nova não estiver definida. O controle por marcador exige `flashsale.demo.enabled=true`.

## Verificar

```bash
mvn -B -ntp -f order-processor-service/pom.xml clean verify
python3 scripts/test-real-flow.py
```
A consulta pública é GET http://localhost:8080/v1/orders/{id}, no Checkout. Endpoints /internal/mock não existem nesta branch. A versão didática completa está em `versao-dependencias-mockadas`.
