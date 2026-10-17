# Guia do `checkout-service`

O Checkout recebe a compra, valida a entrada, registra a intenção no seu PostgreSQL, reserva estoque no Redis e entrega o evento ao RabbitMQ. Também oferece a consulta pública do pedido e consome compensações. Para seguir a outra metade do fluxo, veja o [guia do Order Processor](./order-processor-service.md).

## Caminho de uma compra

1. [OrderResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderResource.java) recebe `POST /v1/orders` e chama [RealCheckoutService.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java).
2. O serviço valida a entrada, cria um UUID e grava o evento em `checkout_outbox` com estado `PREPARED`. A intenção é persistida **antes** da reserva, para que uma falha entre sistemas possa ser reconciliada.
3. [RedisStockRepository.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RedisStockRepository.java) executa um script Lua: verifica saldo e decrementa o estoque na mesma operação atômica, registrando a reserva por UUID. Saldo insuficiente gera `REJECTED` e HTTP `409`; reserva bem-sucedida muda a intenção para `READY` e o HTTP retorna `202` com `PROCESSING`.
4. [RealWorker.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) roda periodicamente. Ele reconcilia intenções `PREPARED`, publica as `READY` em `order-created` e marca `SENT` após confirmação do broker. Se o RabbitMQ falhar, a intenção continua no outbox para nova tentativa.
5. Em caso de cancelamento, o worker consome `order-compensation`, devolve o estoque via Lua e registra `compensated=true` no outbox local. A ação de devolver é idempotente para o mesmo UUID.

Observações:
* a reserva atômica combate *overselling*;
* a resposta `202` separa aceitação de confirmação;
* `order-created` desacopla os serviços;
* `order-compensation` realiza a etapa compensatória de uma SAGA coreografada.
* `PREPARED`, `READY`, `SENT` e `REJECTED` são **estados da entrega local**, não estados finais do pedido. A tabela local guarda intenção de publicação, enquanto o Processor é o dono do registro final do pedido.

## API e aplicação: arquivo por arquivo

| Arquivo | Papel e conceito demonstrado |
| --- | --- |
| [CreateOrderRequest.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/CreateOrderRequest.java) | Record que traduz o JSON de entrada (`produto_id`, `quantidade`, `cliente_cpf`, `cliente_nome`) para nomes Java. É o contrato de entrada, separado da regra de negócio. |
| [CreateOrderResponse.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/CreateOrderResponse.java) | Record da resposta `202`: UUID, `PROCESSING` e hora da requisição. `PROCESSING` significa que a compra foi aceita para processamento, não que já foi confirmada. |
| [ErrorResponse.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/ErrorResponse.java) | Forma comum do erro com campo `mensagem`. |
| [OrderResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderResource.java) | Endpoint de criação. Usa o bloqueio de leitura de [DemoMaintenance.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/DemoMaintenance.java) para permitir POSTs concorrentes e impedir que a preparação do estoque de demonstração aconteça durante um POST. |
| [OrderValidation.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/OrderValidation.java) | Valida produto, quantidade positiva e CPF sem acessar Redis, banco ou broker. Acumula erros de campos. O nome do cliente não é obrigatório nesta implementação. |
| [RealCheckoutService.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java) | Orquestra validação, intenção durável, reserva, reconciliação, publicação e compensação. `pending` permite consultar a intenção local enquanto o Processor ainda não persistiu o pedido. |
| [CheckoutExceptionMapper.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/CheckoutExceptionMapper.java) | Converte exceções em HTTP: entrada inválida `400`, estoque insuficiente `409`, pedido ausente `404` e erro interno `500`. Reserva de resultado ambíguo retorna `503` **com o UUID**, para consulta sem repetir imediatamente a compra. |
| [InvalidOrderException.java](../checkout-service/src/main/java/br/com/estudo/flashsale/domain/InvalidOrderException.java) e [InsufficientStockException.java](../checkout-service/src/main/java/br/com/estudo/flashsale/domain/InsufficientStockException.java) | Nomeiam falhas de domínio que a camada HTTP transforma em respostas distintas. |
| [DemoMaintenance.java](../checkout-service/src/main/java/br/com/estudo/flashsale/application/DemoMaintenance.java) e [DemoResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/DemoResource.java) | Controlam a preparação e inspeção do estoque de demonstração (`/internal/demo`). O recurso só responde quando `flashsale.demo.enabled=true` e evita sobrescrever saldo com pedidos em trânsito ou compensação pendente. |
| [HealthResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java) | `/health/live` indica processo ativo; `/health/ready` verifica banco, RabbitMQ e Redis. Ilustra a diferença entre vida do processo e disponibilidade das dependências. |

## Mensagens, infraestrutura e persistência

| Arquivo | Papel e conceito demonstrado |
| --- | --- |
| [OrderCreatedEvent.java](../checkout-service/src/main/java/br/com/estudo/flashsale/domain/OrderCreatedEvent.java) | Contrato JSON de `order-created`: UUID, produto, quantidade, cliente e horário. O Processor possui seu próprio record equivalente; a mensagem é o contrato entre os serviços. |
| [OrderCompensationEvent.java](../checkout-service/src/main/java/br/com/estudo/flashsale/domain/OrderCompensationEvent.java) | Contrato recebido de `order-compensation`: identifica qual reserva reverter e informa o motivo. |
| [RedisStockRepository.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RedisStockRepository.java) | Scripts Lua manipulam `stock:{produto}` e `reservation:{pedido}` juntos. A reserva impede saldo negativo e reexecução do mesmo UUID não decrementa duas vezes. A compensação só devolve uma reserva `RESERVED` correspondente; nova entrega de uma compensação `COMPLETED` não soma estoque outra vez. Remove ainda o cache `order:{pedido}`. |
| [RealDatabase.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java) e [schema.sql](../checkout-service/src/main/resources/db/schema.sql) | Inicializam o schema local. `checkout_outbox` guarda payload, estado de entrega, flag de compensação e data. O índice acelera a busca por intenções pendentes. Este banco é segregado do banco do Processor. |
| [RabbitBroker.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java) | Declara filas duráveis, publica mensagens persistentes com *publisher confirms* e consome com ack manual após o handler. Em falha do handler, envia cópia à fila `.retry` com espera de 5 s; na terceira falha, envia à `.dlq`. A cópia é confirmada antes do ack da original. |
| [RealWorker.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) | `@Scheduled(every="1s")` executa consumo de compensações e despacho do outbox, pulando execuções sobrepostas. Problemas transitórios são tentados novamente no próximo ciclo. |
| [application.properties](../checkout-service/src/main/resources/application.properties) | Define porta `8080`, PostgreSQL do Checkout, RabbitMQ, Redis, URL do Processor e timeouts. Os valores `localhost` atendem à execução local; o Compose fornece os endereços internos nos containers. Controles de demonstração começam desabilitados. |
| [pom.xml](../checkout-service/pom.xml) | Módulo Java 21/Quarkus; dependências de REST, JDBC PostgreSQL, Redis, cliente RabbitMQ, REST Client, scheduler e testes. Mostra quais integrações são usadas de fato. |
| [Dockerfile](../checkout-service/Dockerfile) | Compila com Maven/JDK 21 e executa o Quarkus em imagem JRE 21, porta `8080`, usuário sem privilégios. |

## Consulta: Cache-Aside e três tentativas

1. [OrderQueryResource.java](../checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java) recebe `GET /v1/orders/{id}` e procura `order:{id}` no Redis. Um acerto retorna `X-Cache: HIT`.
2. Na ausência de cache, [OrderQueryClient.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/OrderQueryClient.java) consulta `GET /internal/orders/{id}` do Processor. O Checkout **não lê o banco do outro serviço diretamente**; respeita o dono dos dados.
3. Se o Processor responder `404`, há até **três consultas** com 100 ms entre elas, cobrindo a janela entre o `202` e a persistência assíncrona. Após as tentativas, a intenção local pode ser retornada como `PROCESSING` (ou `CANCELLED` se a reserva foi rejeitada); um UUID desconhecido recebe `404`. Indisponibilidade do Processor gera `503`, sem ser confundida com pedido inexistente.
4. [OrderCache.java](../checkout-service/src/main/java/br/com/estudo/flashsale/ports/OrderCache.java) define a porta de cache e [RedisOrderCache.java](../checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RedisOrderCache.java) implementa `GET`/`SET EX 300`. Somente `CONFIRMED` é cacheado por cinco minutos. `PROCESSING` e `CANCELLED` não são cacheados para evitar resposta desatualizada. Falha do cache não impede a consulta ao Processor.

Essa sequência é **Cache-Aside**: a aplicação consulta o cache, busca a fonte proprietária na ausência e preenche o cache. As três tentativas de consulta HTTP são diferentes das três falhas de consumo que levam uma mensagem à DLQ.

## Testes e arquivos de apoio

| Arquivo | O que verificar ao estudá-lo |
| --- | --- |
| [OrderValidationTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/application/OrderValidationTest.java) | Campos obrigatórios, quantidade inválida e nome opcional; testes unitários da regra sem infraestrutura. |
| [RealCheckoutServiceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/application/RealCheckoutServiceTest.java) | Ordem entre intenção e reserva, rejeição de estoque, falha do banco, reserva ambígua e reconciliação idempotente. |
| [OrderResourceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderResourceTest.java) | Contrato HTTP `202`, erros `400`/`409` e UUID recuperável no `503`. |
| [OrderQueryResourceTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java) | `HIT`/`MISS`, cache só de confirmados, falha do Redis, intenção local, `404` versus `503`. |
| [DemoDisabledTest.java](../checkout-service/src/test/java/br/com/estudo/flashsale/api/DemoDisabledTest.java) e [application.properties de teste](../checkout-service/src/test/resources/application.properties) | Controle de demonstração desligado por padrão; testes isolados desabilitam datasource e scheduler reais. |
| [README.md](../checkout-service/README.md) | Instruções específicas de execução e verificação do módulo. Os Dockerfiles alternativos em `src/main/docker/` e o Maven Wrapper são arquivos de empacotamento; não participam do fluxo de negócio descrito aqui. |

Para observar o fluxo entre serviços, filas, bancos e Redis, siga a [demonstração](./demonstracao.md). A validação com infraestrutura real está no roteiro do [README principal](../README.md).
