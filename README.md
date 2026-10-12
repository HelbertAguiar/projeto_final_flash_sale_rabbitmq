# Flash Sale — Checkout e Order Processor

Java 21, Quarkus e Maven. Implementação do [enunciado](Projeto_final.md) com dois modos de execução: **mock** (HTTP e memória) e **real** (Redis, RabbitMQ e PostgreSQL). Cada microsserviço tem seu próprio banco.

## Escolha como executar

Execute os comandos na raiz. Scripts usam Bash/Linux; para JVMs locais instale **JDK 21, Maven, Python 3 e curl**. Configure `JAVA_HOME` para o JDK 21 e confira `mvn -version`. Docker Compose é necessário para infraestrutura e containers; execução completa em Docker não precisa de Java/Maven instalados na máquina. As primeiras execuções baixam dependências e imagens.

| Cenário | Comando | Onde estão as aplicações | Dependências |
|---|---|---|---|
| Mock atual | `bash scripts/run-mock.sh` | Duas JVMs locais | HTTP, estoque, reservas, fila e pedidos em memória |
| Mock com falha | `bash scripts/run-mock-failure.sh` | Duas JVMs locais | Mesmo mock; pedidos são cancelados |
| Somente infraestrutura | `bash scripts/run-infrastructure.sh` | Você inicia as aplicações | Redis, RabbitMQ e dois PostgreSQL no Docker |
| Real com JVMs locais | `bash scripts/run-real-local.sh` | Duas JVMs locais | Infraestrutura Docker, iniciada pelo script |
| Tudo em Docker | `bash scripts/run-docker.sh` | Dois containers Java | Infraestrutura Docker |
| Docker com falha | `bash scripts/run.sh docker-failure` | Dois containers Java | Mesma infraestrutura; Processor cancela pedidos |
| Encerrar Docker | `bash scripts/stop-docker.sh` | — | Preserva os volumes |

**Use um cenário por vez nas mesmas portas.** Ctrl+C nos launchers locais encerra as duas JVMs que o script iniciou. Containers de infraestrutura continuam ativos. Logs locais ficam em `.run/checkout.log` e `.run/processor.log`; os scripts verificam prontidão antes de anunciar o ambiente.

### 1. Mock — comportamento atual preservado

```bash
bash scripts/run-mock.sh
# Em outro terminal:
bash scripts/demo-success.sh
```

Para falha, encerre o launcher anterior e execute:

```bash
bash scripts/run-mock-failure.sh
# Em outro terminal:
bash scripts/demo-compensation.sh
```

O mock reinicializa `PROD-001=10` e `PROD-002=0` ao reiniciar o Checkout. HTTP liga os dois serviços. Fila, pedidos, reservas e idempotência são voláteis. Falha de publicação devolve a reserva e retorna 503. Não há garantias duráveis de broker; timeout HTTP pode ter resultado ambíguo.

O launcher empacota no perfil `test` para manter os endpoints de inspeção e força `checkout.events.transport=http`; `-DskipTests` pula a execução dos testes, não os altera. Para desenvolver com hot reload, use dois terminais:

```bash
mvn -f order-processor-service/pom.xml quarkus:dev
mvn -f checkout-service/pom.xml quarkus:dev
```

Checkout isolado: acrescente `-Dcheckout.events.transport=memory`. Simulação de falha no Processor: `-Dprocessor.mock.failure-mode=always`. Este valor funciona também no modo real.

### 2. Dependências reais e aplicações locais

```bash
bash scripts/run-real-local.sh
# Em outro terminal:
bash scripts/demo-success.sh
```

Para controlar cada aplicação pela IDE, suba primeiro `bash scripts/run-infrastructure.sh`, e configure os dois processos com `-Dquarkus.profile=real`. No Maven:

```bash
mvn -f order-processor-service/pom.xml quarkus:dev -Dquarkus.profile=real
mvn -f checkout-service/pom.xml quarkus:dev -Dquarkus.profile=real
```

Os endereços padrão são `localhost` com as portas da tabela abaixo. Se personalizar portas/senhas, ajuste também as propriedades da IDE. O launcher `real-local` lê a configuração e faz esse mapeamento automaticamente.

### 3. Tudo em Docker

```bash
bash scripts/run-docker.sh
bash scripts/demo-success.sh
docker compose ps
docker compose logs -f checkout-service order-processor-service
```

Os [Dockerfiles](checkout-service/Dockerfile) ([Processor](order-processor-service/Dockerfile)) compilam com Maven/JDK 21 e executam com JRE 21 como usuário sem privilégios. O [Compose](compose.yml) aguarda healthchecks de infraestrutura e a inicialização do estoque. O perfil real é selecionado em runtime pelo mesmo artefato JVM.

Para demonstrar compensação:

```bash
bash scripts/run.sh docker-failure
bash scripts/demo-compensation.sh
# Voltar ao processamento normal:
bash scripts/run-docker.sh
```

Trocar o modo recria a aplicação quando sua configuração muda; não reinicializa o estoque nem apaga pedidos. O script de compensação aguarda a reserva chegar a `COMPLETED` e mostra o estoque. Se as demonstrações consumirem as 10 unidades de PROD-001, use um novo produto de demonstração ou ajuste explicitamente seu estoque.

## Configuração e conexões

Copie [.env.example](.env.example) para `.env` se quiser personalizar. O Compose e os scripts leem esse arquivo; aplicações iniciadas diretamente por Maven/IDE não o leem automaticamente. Use sintaxe compatível com Bash e Compose. Credenciais padrão são para desenvolvimento local.

| Dependência | Máquina local | Dentro da rede Docker |
|---|---|---|
| Checkout | `localhost:8080` | `checkout-service:8080` |
| Processor | `localhost:8081` | `order-processor-service:8081` |
| Redis | `localhost:6379` | `redis:6379` |
| RabbitMQ AMQP | `localhost:5672` | `rabbitmq:5672` |
| RabbitMQ Management | `http://localhost:15672` | `rabbitmq:15672` |
| Banco Checkout | `localhost:5433/checkout` | `checkout-db:5432/checkout` |
| Banco Processor | `localhost:5434/processor` | `processor-db:5432/processor` |

RabbitMQ: usuário/senha `flashsale/flashsale`, vhost `/flashsale`. PostgreSQL: `checkout/checkout` e `processor/processor`. Portas publicadas ficam restritas a 127.0.0.1. Dentro de um container, `localhost` aponta para ele próprio; por isso o Compose injeta os nomes DNS da tabela.

### Chaveamento e pontos de alteração

`QUARKUS_PROFILE=real` ativa `%real.flashsale.real=true`. Sem esse perfil, o modo é mock. O pool JDBC fica inativo no mock e Dev Services automáticos estão desabilitados, inclusive Compose/Redis: executar mocks não sobe containers.

| Ponto | Arquivos |
|---|---|
| Seleção do fluxo Checkout | [OrderResource](checkout-service/src/main/java/br/com/estudo/flashsale/api/OrderResource.java), [configuração Checkout](checkout-service/src/main/resources/application.properties) |
| Estoque/reserva reais e publicação pendente | [RealCheckoutService](checkout-service/src/main/java/br/com/estudo/flashsale/application/RealCheckoutService.java), [RedisStockRepository](checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RedisStockRepository.java) |
| Mock HTTP/memória do Checkout | [OrderPublisherProducer](checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/OrderPublisherProducer.java), [CheckoutService](checkout-service/src/main/java/br/com/estudo/flashsale/application/CheckoutService.java), [ReservationService](checkout-service/src/main/java/br/com/estudo/flashsale/application/ReservationService.java) |
| Fluxo real Processor | [PostgresOrderRepository](order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/PostgresOrderRepository.java), [configuração Processor](order-processor-service/src/main/resources/application.properties) |
| Consumo e publicação RabbitMQ | [RabbitBroker Checkout](checkout-service/src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java), [RabbitBroker Processor](order-processor-service/src/main/java/br/com/estudo/flashsale/infrastructure/RabbitBroker.java), respectivos `RealWorker` |
| Esquemas PostgreSQL | [Checkout](checkout-service/src/main/resources/db/schema.sql), [Processor](order-processor-service/src/main/resources/db/schema.sql) |
| Dependências Maven | [Checkout](checkout-service/pom.xml), [Processor](order-processor-service/pom.xml) |

A implementação real usa JDBC com transações explícitas e o cliente Java RabbitMQ; Redis usa a extensão Quarkus. Assim, locks, confirmação do broker e gravação do outbox ficam visíveis no código. A orquestração real é separada do mock porque a sequência durável de reserva/publicação é diferente. Não basta mudar `checkout.events.transport` para ativar as dependências reais: essa propriedade continua escolhendo apenas o transporte do mock.

Principais variáveis das aplicações: `QUARKUS_PROFILE`, `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD`, `QUARKUS_REDIS_HOSTS`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VIRTUAL_HOST`, `QUARKUS_REST_CLIENT_PROCESSOR_URL` e `PROCESSOR_MOCK_FAILURE_MODE`.

## Fluxo real e recuperação

1. Checkout valida a compra e grava uma intenção `PREPARED` no PostgreSQL próprio.
2. Um script Lua reserva estoque e registra o UUID no Redis atomicamente. Saldo insuficiente retorna 409; a rejeição também é idempotente.
3. A intenção passa a `READY`; o POST retorna 202. Um worker publica em `order-created` e marca `SENT` após confirmação do RabbitMQ.
4. Processor grava resultado e eventual compensação pendente na mesma transação PostgreSQL. UUID único evita processar pedidos duplicados.
5. Outro outbox entrega a compensação. Checkout devolve estoque e marca `COMPLETED` atomicamente no Redis; registra a conclusão também no banco próprio.

Se o broker cair, a publicação permanece no banco e é tentada novamente a cada ciclo do worker. **No modo real, broker indisponível pode coexistir com POST 202:** reserva e publicação pendente já foram registradas. Um timeout de confirmação pode causar reenvio, por isso consumidores são idempotentes. Nunca se devolve estoque apenas por um timeout de publicação.

Se houver erro entre a intenção durável e a conclusão da reserva, o POST retorna **503 com pedido_id**. A reconciliação retoma essa intenção. Consulte esse UUID antes de repetir a compra: o resultado pode ser aceitação ou rejeição posterior. A API ainda não implementa chave de idempotência fornecida pelo cliente.

Filas principais duráveis: `order-created` e `order-compensation`. Cada uma tem `.retry` (atraso de 5 s) e `.dlq`. São até 3 tentativas por mensagem; depois a mensagem fica na DLQ para inspeção e reenvio após corrigir a causa. Falha no PostgreSQL do Processor não confirma o consumo nem inventa um cancelamento que não conseguiu persistir. Falhas de publicação do outbox continuam pendentes, sem limite de tentativas.

O consumidor usa polling em lotes de até 20 mensagens por ciclo de 1 s, com ack manual. O Management pode mostrar zero consumidores registrados porque não utiliza `basicConsume`. O código é didático e privilegia a leitura das transações.

`PUBLISHED` no campo `compensacao` do Processor significa confirmação pelo broker. A devolução efetiva é confirmada pelo registro `reservation:<pedido_id>` no Redis e `compensated=true` no banco Checkout. Não há evento de retorno atualizando o Processor para `COMPLETED` no modo real.

## API e Cache-Aside

```bash
curl -i http://localhost:8080/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Fernando Augusto"}'

# Use o pedido_id retornado:
curl http://localhost:8080/v1/orders/SEU_UUID
curl http://localhost:8080/health/ready
curl http://localhost:8081/health/ready
```

O GET busca no Redis e, na ausência de cache, consulta o banco do Processor **por sua API interna**, preservando a propriedade do banco. Pedidos confirmados têm TTL de 300 s; estados transitórios e cancelamentos não são cacheados para não ocultar progresso da compensação. Há até 3 buscas com intervalo de 100 ms para resultados ausentes antes de 404; indisponibilidade do Processor retorna 503. O mock consulta o Processor por HTTP, sem Redis, e pode retornar 404 antes do consumo da fila em memória.

A base do Checkout guarda reservas/intenção de publicação; somente o Processor guarda o resultado do processamento. Se o Processor ainda não conhece o UUID após três buscas, a consulta real verifica a intenção local: retorna PROCESSING enquanto aguarda publicação/processamento, ou CANCELLED se a recuperação detectou estoque insuficiente. UUID ausente nos dois serviços retorna 404. `/internal/orders/{id}` é usado na consulta entre serviços. Endpoints de entrada HTTP do mock rejeitam chamadas no modo real.

## Inspeção, persistência e encerramento

```bash
docker compose exec redis redis-cli GET stock:PROD-001
docker compose exec redis redis-cli HGETALL reservation:SEU_UUID
docker compose exec checkout-db psql -U checkout -d checkout -c "SELECT pedido_id,state,compensated FROM checkout_outbox"
docker compose exec processor-db psql -U processor -d processor -c "SELECT pedido_id,payload FROM orders"
docker compose logs --tail=100 checkout-service order-processor-service
bash scripts/stop-docker.sh
```

Volumes nomeados preservam bancos, RabbitMQ e Redis. Redis usa AOF e `appendfsync always`; o seed usa `SET NX`, sem repor estoque já consumido. Esquemas são inicializados com DDL idempotente em [schema.sql do Checkout](checkout-service/src/main/resources/db/schema.sql) e [Processor](order-processor-service/src/main/resources/db/schema.sql). Alterações futuras de esquema exigem migrações; não há atualização automática de estruturas existentes.

`docker compose down` preserva dados. **`docker compose down -v` apaga todos os dados deste projeto Compose** e deve ser usado somente quando você quiser começar do zero. Scripts normais não removem volumes.

Se já usava o Compose antigo com volume RabbitMQ existente, `RABBITMQ_DEFAULT_*` não recria usuários/vhosts. Crie `/flashsale` e conceda permissões pelo Management; não é necessário apagar o volume. O hostname fixo `rabbitmq` mantém a identidade do broker nas recriações.

## Testes

```bash
mvn -f checkout-service/pom.xml test
mvn -f order-processor-service/pom.xml test

# Teste HTTP existente: duas JVMs, sem Docker
mvn -f checkout-service/pom.xml package -DskipTests -Dquarkus.profile=test
mvn -f order-processor-service/pom.xml package -DskipTests -Dquarkus.profile=test
python3 scripts/test-local-flow.py

# Integração real: projeto Compose exclusivo, portas livres, limpeza ao finalizar
python3 scripts/test-real-flow.py
```

Os testes existentes preservam regras, contratos, reserva concorrente e compensação idempotente do mock. O [teste real](scripts/test-real-flow.py) verifica sucesso, cache TTL, bancos separados, reinícios, indisponibilidade do broker, recuperação do outbox, concorrência, compensação duplicada e DLQ. Cria um projeto Compose de nome exclusivo e remove somente seus próprios containers e volumes no final. Os scripts de demonstração usam um pedido real em cada execução e consomem estoque quando há sucesso.

## Limites desta entrega

O modo real usa uma instância de cada dependência, sem replicação/alta disponibilidade. Redis é a autoridade do estoque e do registro de idempotência; seus dados devem ser preservados junto aos bancos. Não há transação distribuída entre PostgreSQL e Redis: a intenção persistida e scripts idempotentes permitem retomar operações, mas perder os volumes quebra essa garantia. Reservas e outboxes ainda não têm política de retenção.

Sem autenticação, pagamento real ou operação em produção. A simulação `always` representa falha de negócio; não equivale a desligar o banco. Em indisponibilidade prolongada, inspecione DLQs e reconcilie pedidos antes de reenviar. Os Dockerfiles desta entrega usam JVM; os Dockerfiles nativos originais não fazem parte do fluxo validado.
