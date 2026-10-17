# Guia do `order-processor-service`

O Processor consome `order-created`, decide o resultado do pedido, persiste o estado no seu PostgreSQL e, em cancelamentos, publica uma intenção durável de compensação para o Checkout. A entrada pública e a reserva estão no [guia do Checkout](./checkout-service.md).

## Caminho de uma mensagem

1. [RealWorker.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) procura mensagens em `order-created` a cada segundo e chama [PostgresOrderRepository.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/PostgresOrderRepository.java).
2. O repositório valida o evento e cria um [Order.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/domain/Order.java) inicialmente `PROCESSING`. [ProcessingPolicy.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/application/ProcessingPolicy.java) decide `CONFIRMED` ou `CANCELLED`.
3. O pedido final é inserido em `orders`. Para `CANCELLED`, a mensagem de compensação é inserida em `compensation_outbox` **na mesma transação do banco**. Assim, a decisão de cancelar e a intenção de devolver estoque sobrevivem juntas a uma falha.
4. O broker confirma o consumo só depois de o handler concluir. Entrega duplicada com o mesmo UUID não cria outra linha nem outra intenção: o `pedido_id` é chave primária e o insert usa `ON CONFLICT DO NOTHING`.
5. O worker publica outboxes pendentes em `order-compensation`. Depois do *publisher confirm*, marca `published=true` e atualiza `compensacao` para `PUBLISHED`. O Checkout precisa consumir essa mensagem para **efetivamente** devolver o estoque; `PUBLISHED` não comprova devolução.

Observações:
* consumidor assíncrono, banco segregado, ciclo de vida do pedido e SAGA coreografada.
* Falha de negócio controlada leva a `CANCELLED` e compensação.
* Erro técnico de banco ou mensagem leva a nova tentativa e possivelmente DLQ; não deve ser interpretado automaticamente como cancelamento de negócio.

## Domínio e decisão: arquivo por arquivo

| Arquivo | Papel e conceito demonstrado |
| --- | --- |
| [OrderCreatedEvent.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/domain/OrderCreatedEvent.java) | Record do contrato JSON recebido de `order-created`. Reproduz os campos publicados pelo Checkout sem compartilhar classes Java entre serviços. |
| [OrderStatus.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/domain/OrderStatus.java) | Define os estados `PROCESSING`, `CONFIRMED` e `CANCELLED`. A distinção é central para a consulta do pedido. |
| [Order.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/domain/Order.java) | Entidade imutável representada como record. `processing` cria o estado inicial; `finish` aceita transição para um estado terminal e registra histórico. Um pedido cancelado inicia compensação como `PENDING`. `compensationEvent` monta a mensagem para desfazer a reserva. |
| [OrderCompensationEvent.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/domain/OrderCompensationEvent.java) | Contrato publicado em `order-compensation`: UUID, produto, quantidade e motivo; o Checkout usa esses dados para localizar a reserva correta. |
| [ProcessingPolicy.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/application/ProcessingPolicy.java) | Regra de decisão isolada da infraestrutura. `processor.failure-mode=never` confirma; `always` cancela. Marcadores de demonstração permitem cancelar pedidos específicos pelo `cliente_nome`, sem afetar os outros. É a simulação de falha prevista no enunciado, não um pagamento real. |

## Infraestrutura e API interna

| Arquivo | Papel e conceito demonstrado |
| --- | --- |
| [PostgresOrderRepository.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/PostgresOrderRepository.java) | Valida e persiste o evento; grava cancelamento e outbox na mesma transação; ignora reentrega pelo UUID; consulta pedidos; publica compensações pendentes com bloqueio `FOR UPDATE SKIP LOCKED`. Esse é o ponto central de persistência, idempotência e *transactional outbox*. |
| [schema.sql](../order-processor-service/src/main/resources/db/schema.sql) | `orders` guarda o JSON do pedido por UUID; `compensation_outbox` referencia o pedido e registra se houve publicação. As tabelas pertencem ao banco do Processor, separado do banco do Checkout. |
| [RealDatabase.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealDatabase.java) | Fornece conexões do pool e inicializa o schema local uma vez. `ready` confirma inicialização e conexão válida. |
| [RabbitBroker.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java) | Declara filas principais, `.retry` e `.dlq`; usa mensagens persistentes e *publisher confirms*. O consumo é feito por `basicGet` em lotes, com ack manual após processamento. Em erro, copia para `.retry` com TTL de 5 s; na terceira falha, envia à `.dlq`, confirmando a cópia antes do ack original. |
| [RealWorker.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RealWorker.java) | `@Scheduled(every="1s")` inicializa o banco, consome `order-created` e despacha a outbox de compensação. Não executa ciclos sobrepostos. Um erro transitório deixa trabalho para o próximo ciclo. |
| [OrderQueryResource.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/api/OrderQueryResource.java) | Oferece `GET /internal/orders/{id}` ao Checkout. Retorna o registro persistido ou `404`. O cliente consulta publicamente `GET /v1/orders/{id}` no Checkout, que aplica Cache-Aside. |
| [HealthResource.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/api/HealthResource.java) | `/health/live` informa que o processo está vivo; `/health/ready` depende do banco e do RabbitMQ. |
| [DemoResource.java](../order-processor-service/src/main/java/br/com/estudo/flashsale/api/DemoResource.java) | `POST /internal/demo/failures/{marker}` ativa uma falha para um marcador válido quando `flashsale.demo.enabled=true`. O controle responde `404` se estiver desabilitado. |
| [application.properties](../order-processor-service/src/main/resources/application.properties) | Porta `8081`, PostgreSQL próprio, RabbitMQ, porta dos testes e política de falha. O modo padrão é `never`; há alias antigo `processor.mock.failure-mode`. O controle de demonstração começa desabilitado. |
| [pom.xml](../order-processor-service/pom.xml) | Módulo Java 21/Quarkus com REST, JDBC PostgreSQL, cliente RabbitMQ, scheduler e bibliotecas de teste. |
| [Dockerfile](../order-processor-service/Dockerfile) | Compila com Maven/JDK 21 e executa em JRE 21, porta `8081`, usuário sem privilégios. |

## Como ler os estados e as garantias

`CONFIRMED` indica que a decisão foi persistida pelo Processor. `CANCELLED` indica falha de negócio simulada e intenção de compensação persistida. No campo `compensacao`, `PENDING` significa que a intenção ainda não foi publicada; `PUBLISHED` significa que o RabbitMQ confirmou a mensagem. O código do Processor **não recebe confirmação de que o Checkout terminou a devolução**. Para demonstrar a SAGA completa, confira também o saldo e a reserva no Redis e `compensated=true` no outbox do Checkout.

As filas `.retry` e `.dlq` tratam falhas técnicas de processamento. Isso é diferente das **três tentativas do GET** no Checkout: ali a aplicação repete uma consulta `404` enquanto espera a persistência assíncrona. No broker, a mensagem é reentregue após erro do handler e pode terminar na DLQ para investigação.

## Testes e arquivos de apoio

| Arquivo | O que verificar ao estudá-lo |
| --- | --- |
| [OrderTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/domain/OrderTest.java) | Histórico, identidade do pedido, evento de compensação e proibição de transição após estado terminal. |
| [ProcessingPolicyTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/application/ProcessingPolicyTest.java) | Falha direcionada por marcador, confirmação dos outros pedidos e resultado estável em reentrega. |
| [OrderQueryResourceTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/api/OrderQueryResourceTest.java) | Contrato JSON da consulta interna, `404` para ausente e erros de identificador. |
| [DemoDisabledTest.java](../order-processor-service/src/test/java/br/com/estudo/flashsale/api/DemoDisabledTest.java) e [application.properties de teste](../order-processor-service/src/test/resources/application.properties) | Controle de demonstração desligado por padrão; testes isolados sem datasource e scheduler reais. |
| [README.md](../order-processor-service/README.md) | Execução do módulo, configuração de falha controlada e comandos de verificação. |

Para observar a mensagem nas duas filas, a persistência, o cache e a devolução do estoque, siga a [demonstração](./demonstracao.md). A validação com infraestrutura real está no [README principal](../README.md).
