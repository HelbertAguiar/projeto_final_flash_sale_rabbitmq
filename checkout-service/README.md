# Checkout Service

Esta é a branch `versao-dependencias-mockadas`. O caminho recomendado é `bash scripts/run-mock.sh`; instruções completas e testes estão no [README principal](../README.md). As referências reais abaixo descrevem adaptadores herdados e inativos no modo mockado.

Java 21 / Quarkus. API pública: `POST /v1/orders` e `GET /v1/orders/{pedido_id}`.

## Executar

Na raiz do repositório:

```bash
bash scripts/run-mock.sh       # Dois serviços: HTTP + memória
bash scripts/run-real-local.sh # Dependências Docker + duas JVMs
bash scripts/run-docker.sh     # Aplicações e dependências em Docker
```

Escolha um comando por vez. Consulte o [README principal](../README.md) para pré-requisitos, falhas, demonstrações e parada.

Para iniciar somente este serviço no modo mock: `mvn -f checkout-service/pom.xml quarkus:dev`. O Processor precisa estar na porta 8081. Acrescente `-Dcheckout.events.transport=memory` para publicação isolada em memória.

Para usar infraestrutura real: inicie `bash scripts/run-infrastructure.sh` e execute `mvn -f checkout-service/pom.xml quarkus:dev -Dquarkus.profile=real`. Inicie também o Processor com o mesmo perfil.

## Chaveamento e configuração

- [application.properties](src/main/resources/application.properties): `%real.flashsale.real=true` seleciona Redis, RabbitMQ e PostgreSQL.
- [OrderResource](src/main/java/br/com/estudo/flashsale/api/OrderResource.java): delega ao fluxo mock ou real.
- [RealCheckoutService](src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java): grava intenção no banco, reserva no Redis e publica via outbox.
- [RedisStockRepository](src/main/java/br/com/estudo/flashsale/infrastructure/RedisStockRepository.java): reserva e devolução Lua, idempotentes por UUID.
- [OrderPublisherProducer](src/main/java/br/com/estudo/flashsale/infrastructure/OrderPublisherProducer.java): seleção `http|memory` do mock.
- [OrderQueryResource](src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java): Cache-Aside, TTL 300 s para confirmados e três buscas antes de 404.
- [schema.sql](src/main/resources/db/schema.sql): tabela `checkout_outbox`, estado de publicação e registro da compensação.
- [Dockerfile](Dockerfile): build Maven/JDK 21 e execução JRE 21 sem privilégios.

No host: Redis localhost:6379, RabbitMQ localhost:5672, PostgreSQL localhost:5433/checkout e Processor localhost:8081. No container, o [Compose](../compose.yml) injeta os nomes DNS internos. O pool JDBC e workers reais ficam desativados no mock.

O mock retorna 503 e restaura a reserva ao falhar a publicação. O real retorna 202 após reservar e guardar a intenção de entrega; broker indisponível mantém o outbox pendente. Erro na reconciliação inicial retorna 503 com UUID para acompanhamento, sem restauração prematura de uma publicação ambígua.

`/health/live` informa processo ativo; `/health/ready` verifica as dependências no modo real. Entrada de compensação por HTTP é exclusiva do mock. No real, mensagens chegam por RabbitMQ.

## Verificar

```bash
mvn -f checkout-service/pom.xml test
bash scripts/demo-success.sh
bash scripts/demo-compensation.sh # Requer Processor no modo always
```
