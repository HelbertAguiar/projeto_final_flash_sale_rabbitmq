# Separação das dependências mockadas

## Referências Git

- Base comum: `f247874b2292a064721b55bc6c14d1524a4af754`.
- Branch didática: `versao-dependencias-mockadas`, commit `5ba9b2fc8388f1b16bb158295a7c28eca5e6e0e2`.
- Branch original: `main`; o commit que contém este relatório implementa a separação.
- Não houve reescrita de histórico, push ou inclusão de alterações pessoais. A árvore estava limpa ao iniciar; configurações locais ignoradas foram preservadas.

## Preservação e independência

A branch didática contém os dois serviços completos, seus contratos, fixtures, testes e scripts. Foram preservados estoque/reservas/cache/pedidos em memória, fila limitada, consumidor local, eventos e compensações HTTP, deduplicação, retry manual, falhas controladas e verificações de concorrência. PROD-001 começa com 10 unidades; PROD-002 com zero.

Seu inicializador fixa o modo selecionado para evitar que uma variável externa ative infraestrutura real por engano. Os adaptadores reais herdados foram mantidos inativos, evitando uma segunda refatoração desnecessária. Executar MOCK não exige Docker, Redis, PostgreSQL ou RabbitMQ; compilar pela primeira vez exige resolver dependências Maven.

Na main, os recursos exclusivamente mockados foram removidos do código executável. Não há checkout, importação ou leitura de arquivos da branch didática: sua eventual exclusão não afeta a main.

## Adaptações na main

- Extração da validação de entrada, sem alteração das regras, para OrderValidation. A aplicação real não depende mais do antigo CheckoutService mockado.
- Remoção dos repositórios/fila/cache em memória, publicação HTTP de eventos, endpoints /internal/mock e seletores de modo.
- API pública e consultas delegam diretamente aos serviços reais. Prontidão sempre verifica infraestrutura e informa mode=real.
- Configurações e launchers agora inicializam somente integrações reais. Redis, PostgreSQL, RabbitMQ, schemas e mecanismos de outbox/retry/DLQ foram preservados.
- Remoção do cliente REST do Processor e de sua dependência Maven, usados somente para compensação HTTP mockada. O cliente REST de consulta do Checkout continua necessário.
- Falhas didáticas permanecem: simulam decisão de negócio, sem substituir infraestrutura. processor.failure-mode é o nome atual; processor.mock.failure-mode permanece como alias de compatibilidade.
- Mockito permanece com escopo test. Pool e scheduler são desativados apenas pelos recursos de teste, ausentes do artefato executável.

## Cobertura preservada e adaptada

| Antes | Destino |
|---|---|
| Validação do CheckoutService | OrderValidationTest e RealCheckoutServiceTest exercitam a validação e os serviços reais. |
| Contratos HTTP de criação | OrderResourceTest, mais contratos verificados contra infraestrutura real. |
| Reserva, falha de publicação e compensação em memória | Originais preservados na branch didática; main testa intenção durável, recuperação, concorrência e compensação no fluxo real. |
| Fila limitada e recebimento HTTP mockado | Originais exclusivos da branch didática; main verifica RabbitMQ, duplicidade, retry e DLQ. |
| Histórico/estados e política de processamento | OrderTest e ProcessingPolicyTest na main, além do fluxo real. |
| Cache local | Original na branch didática; OrderQueryResourceTest verifica MISS/HIT, estados não cacheáveis e indisponibilidade; integração verifica Redis e TTL. |
| Controles desligados por padrão | DemoDisabledTest preservado nos dois módulos. |
| Exclusão dos endpoints mockados | Testes HTTP de ambos os módulos verificam 404. |

## Validação em 15/10/2026

Ambiente Linux, JDK 21, Maven 3.9.11, Docker Engine 29.9.0, Compose 5.6.0. O Maven inicialmente usava JDK 25; JAVA_HOME/PATH foram ajustados somente nos processos de validação.

| Verificação | Resultado |
|---|---|
| Base e branch mockada: clean verify | 18 testes Checkout + 12 Processor aprovados; empacotamento aprovado. |
| Mock: build no perfil mock | Ambos os módulos aprovados. |
| Mock: test-local-flow.py | Aprovado: indisponibilidade, duplicidade, cancelamento, retry, concorrência e duas execuções do roteiro de 12 passos. |
| Mock: run-mock.sh, demo-mock.sh, demo-success.sh | Aprovados; duas JVMs UP/mode=mock. Nenhum container executando e nenhuma infraestrutura escutando nas portas locais padrão durante a verificação. |
| Main: clean verify | 19 testes Checkout + 8 Processor aprovados; empacotamento aprovado. |
| Main: artefatos | Sem classes InMemory/HttpMock ou endpoints mockados; sem dependências Mockito de produção. |
| Main: execução sem infraestrutura | JVM inicia; live=200/UP, ready=503/DOWN/mode=real, inspeção mockada=404. |
| Main: test-real-flow.py | Aprovado: 12 passos, contratos, cache TTL, persistência após reinício, outbox após falha RabbitMQ, recuperação Redis, concorrência, compensação idempotente e DLQ. Projeto temporário removido ao terminar. |
| Main: start.sh real | Build final das imagens e inicialização aprovados; seis serviços saudáveis, duas APIs UP/mode=real. |
| Main: run-real-local.sh | Build e duas JVMs locais aprovados contra infraestrutura real; UP/mode=real e endpoints mockados=404. |

As JVMs e os containers usados na validação foram encerrados; os volumes do ambiente normal foram preservados. O projeto temporário do teste real removeu somente os próprios volumes.

Os testes Java são executados pelo Surefire. O Failsafe permanece desabilitado no POM original; os testes ponta a ponta Python foram executados separadamente, sem contabilizá-los como testes Maven. Windows/PowerShell e compilação nativa não foram executados. O roteiro de 12 passos foi validado por automação; a inspeção visual na interface de gerenciamento RabbitMQ não foi realizada.

## Executar e alternar

Antes de trocar, encerre as JVMs ou os containers da execução atual, preservando volumes. Recompile ao mudar de branch porque target é ignorado pelo Git.

```bash
git switch versao-dependencias-mockadas
bash scripts/run-mock.sh
# Em outro terminal:
bash scripts/demo-mock.sh
```

```bash
git switch main
bash scripts/start.sh real
bash scripts/demo-real.sh
```

Em ambas:
```bash
mvn -B -ntp -f checkout-service/pom.xml clean verify
mvn -B -ntp -f order-processor-service/pom.xml clean verify
```

Para reproduzir a integração real isolada: `python3 scripts/test-real-flow.py`. Para a integração mockada, compile primeiro ambos os módulos com `package -DskipTests -Dquarkus.profile=mock` e execute `python3 scripts/test-local-flow.py` naquela branch.

## Inventário de arquivos

A lista compara cada branch com a base comum. Arquivos removidos na main continuam disponíveis na branch didática. Nenhum arquivo de produção real depende deles.

### Branch versao-dependencias-mockadas

| Ação | Arquivo |
|---|---|
| Alterado | `README-MOCK.md` (na branch didática) |
| Alterado | [README.md](../README.md) |
| Alterado | [checkout-service/README.md](../checkout-service/README.md) |
| Alterado | [docs/TESTES.md](../docs/TESTES.md) |
| Alterado | [order-processor-service/README.md](../order-processor-service/README.md) |
| Alterado | [scripts/run.sh](../scripts/run.sh) |

### Branch main

| Ação | Arquivo |
|---|---|
| Removido | `README-MOCK.md` (preservado na branch didática) |
| Alterado | [README.md](../README.md) |
| Alterado | [checkout-service/README.md](../checkout-service/README.md) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/api/CheckoutExceptionMapper.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/CheckoutExceptionMapper.java) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/api/CompensationResource.java` (preservado na branch didática) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/api/DemoResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/DemoResource.java) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/api/MockInspectionResource.java` (preservado na branch didática) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderResource.java) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/application/CheckoutService.java` (preservado na branch didática) |
| Criado | [checkout-service/src/main/java/br/com/estudo/flashsale/application/OrderValidation.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/OrderValidation.java) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/application/ReservationService.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/domain/PublicationFailedException.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/HttpMockOrderEventPublisher.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/InMemoryOrderCache.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/InMemoryOrderEventPublisher.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/InMemoryStockRepository.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/OrderPublisherProducer.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/ProcessorClient.java` (preservado na branch didática) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java) |
| Alterado | [checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/Transport.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/ports/OrderEventPublisher.java` (preservado na branch didática) |
| Removido | `checkout-service/src/main/java/br/com/estudo/flashsale/ports/StockRepository.java` (preservado na branch didática) |
| Alterado | [checkout-service/src/main/resources/application.properties](../checkout-service/src/main/resources/application.properties) |
| Criado | [checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java) |
| Criado | [checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderResourceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderResourceTest.java) |
| Removido | `checkout-service/src/test/java/br/com/estudo/flashsale/application/CheckoutServiceTest.java` (preservado na branch didática) |
| Criado | [checkout-service/src/test/java/br/com/estudo/flashsale/application/OrderValidationTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/application/OrderValidationTest.java) |
| Criado | [checkout-service/src/test/java/br/com/estudo/flashsale/application/RealCheckoutServiceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/application/RealCheckoutServiceTest.java) |
| Removido | `checkout-service/src/test/java/br/com/estudo/flashsale/application/ReservationServiceTest.java` (preservado na branch didática) |
| Removido | `checkout-service/src/test/java/br/com/estudo/flashsale/infrastructure/CheckoutIntegrationTest.java` (preservado na branch didática) |
| Removido | `checkout-service/src/test/java/br/com/estudo/flashsale/infrastructure/CompensationIntegrationTest.java` (preservado na branch didática) |
| Removido | `checkout-service/src/test/java/br/com/estudo/flashsale/infrastructure/InMemoryOrderCacheTest.java` (preservado na branch didática) |
| Criado | [checkout-service/src/test/resources/application.properties](../checkout-service/src/test/resources/application.properties) |
| Alterado | [compose.yml](../compose.yml) |
| Criado | [docs/SEGREGACAO.md](../docs/SEGREGACAO.md) |
| Alterado | [docs/TESTES.md](../docs/TESTES.md) |
| Alterado | [order-processor-service/README.md](../order-processor-service/README.md) |
| Alterado | [order-processor-service/pom.xml](../order-processor-service/pom.xml) |
| Alterado | [order-processor-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/api/MockOrderResource.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/api/OrderMessageResource.java` (preservado na branch didática) |
| Alterado | [order-processor-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/application/OrderProcessingService.java` (preservado na branch didática) |
| Alterado | [order-processor-service/src/main/java/br/com/estudo/flashsale/application/ProcessingPolicy.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/application/ProcessingPolicy.java) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/AsyncOrderConsumer.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/CheckoutClient.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/HttpMockOrderCompensationPublisher.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/InMemoryOrderEventQueue.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/InMemoryOrderRepository.java` (preservado na branch didática) |
| Alterado | [order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java) |
| Alterado | [order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/ports/OrderCompensationPublisher.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/ports/OrderEventQueue.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/main/java/br/com/estudo/flashsale/ports/OrderRepository.java` (preservado na branch didática) |
| Alterado | [order-processor-service/src/main/resources/application.properties](../order-processor-service/src/main/resources/application.properties) |
| Removido | `order-processor-service/src/test/java/br/com/estudo/flashsale/api/OrderMessageResourceTest.java` (preservado na branch didática) |
| Criado | [order-processor-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java) |
| Removido | `order-processor-service/src/test/java/br/com/estudo/flashsale/api/ProcessorIntegrationTest.java` (preservado na branch didática) |
| Removido | `order-processor-service/src/test/java/br/com/estudo/flashsale/application/OrderProcessingServiceTest.java` (preservado na branch didática) |
| Criado | [order-processor-service/src/test/java/br/com/estudo/flashsale/domain/OrderTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/domain/OrderTest.java) |
| Criado | [order-processor-service/src/test/resources/application.properties](../order-processor-service/src/test/resources/application.properties) |
| Alterado | [scripts/demo-flow.py](../scripts/demo-flow.py) |
| Removido | `scripts/demo-mock.sh` (preservado na branch didática) |
| Alterado | [scripts/demo.sh](../scripts/demo.sh) |
| Removido | `scripts/run-mock-failure.sh` (preservado na branch didática) |
| Removido | `scripts/run-mock.sh` (preservado na branch didática) |
| Alterado | [scripts/run.sh](../scripts/run.sh) |
| Alterado | [scripts/start.ps1](../scripts/start.ps1) |
| Alterado | [scripts/start.sh](../scripts/start.sh) |
| Removido | `scripts/test-local-flow.py` (preservado na branch didática) |
