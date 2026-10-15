# Checkout Service

Java 21 / Quarkus. API pública: `POST /v1/orders` e `GET /v1/orders/{pedido_id}`.

## Executar

Na raiz, escolha uma opção:
```bash
bash scripts/start.sh real     # Aplicações e dependências em Docker
bash scripts/run-real-local.sh # Dependências Docker + duas JVMs
```

Para desenvolver só este módulo, inicie `bash scripts/run-infrastructure.sh`, execute `mvn -f checkout-service/pom.xml quarkus:dev -Dquarkus.profile=real` e inicie o Processor separadamente. O perfil padrão também exige infraestrutura real. Pré-requisitos e variáveis: [README principal](../README.md).

## Componentes

- [OrderValidation](src/main/java/br/com/estudo/flashsale/application/OrderValidation.java): validação de entrada preservada sem acesso à infraestrutura.
- [RealCheckoutService](src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java): intenção no PostgreSQL, reserva Redis e publicação pelo outbox.
- [RedisStockRepository](src/main/java/br/com/estudo/flashsale/infrastructure/RedisStockRepository.java): reserva e devolução Lua, idempotentes por UUID.
- [OrderQueryResource](src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java): consulta ao Processor e cache Redis de confirmados, TTL 300 s.
- [schema.sql](src/main/resources/db/schema.sql): checkout_outbox e registro da compensação.
- [application.properties](src/main/resources/application.properties): conexões e configuração.

No host: Redis localhost:6379, RabbitMQ localhost:5672, PostgreSQL localhost:5433/checkout e Processor localhost:8081. O [Compose](../compose.yml) injeta os nomes DNS internos nos containers.

O serviço retorna 202 após reservar e guardar a intenção de entrega. Broker indisponível mantém o outbox pendente. Falha na reconciliação inicial retorna 503 com UUID para acompanhamento, sem devolução prematura de uma reserva ambígua. Compensações chegam pelo RabbitMQ; não há endpoint HTTP mockado.

`/health/live` informa processo ativo; `/health/ready` verifica PostgreSQL, Redis e RabbitMQ. Controles de demonstração só ficam disponíveis quando habilitados.

## Verificar

```bash
mvn -B -ntp -f checkout-service/pom.xml clean verify
python3 scripts/test-real-flow.py
```
O primeiro usa dublês somente em testes; o segundo verifica infraestrutura real em projeto Compose isolado. A versão HTTP/memória completa permanece na branch `versao-dependencias-mockadas`.
