# Flash Sale — Checkout e Order Processor (seções 4.1 e 4.4)

MVP didático em Java 21, Quarkus e Maven, conforme [Projeto_final.md](Projeto_final.md). Dois projetos Maven independentes: não precisam de Redis, RabbitMQ, banco de dados nem Docker Compose. O `compose.yml` existente pertence à evolução futura.

## Arquitetura

```text
Cliente → Checkout :8080 → OrderEventPublisher → HTTP order-created
                                               ↓
                         Order Processor :8081 → fila limitada em memória
                                               ↓ consumidor assíncrono
                                      PROCESSING → CONFIRMED
                                               ou CANCELLED
                                               ↓ OrderCompensationPublisher
Checkout ← HTTP order-compensation ←───────────┘
   ↓ valida reserva original e devolve estoque uma única vez
```

- **Checkout:** mantém `POST /v1/orders`, validação, UUID, reserva atômica e resposta `PROCESSING`. O adaptador HTTP só considera publicação aceita ao receber **202** do Processor. Falha na publicação continua devolvendo a reserva e retornando **503**. `InMemoryOrderEventPublisher` permanece selecionado nos testes; `checkout.events.transport=memory` também permite executar isoladamente.
- **Order Processor:** valida o evento e retorna **202** após inserção numa `ArrayBlockingQueue`; fila cheia retorna **503**. Um consumidor próprio bloqueia em `take()`, sem polling, e é iniciado/encerrado pelo ciclo de vida Quarkus. O consumidor grava `PROCESSING` e depois `CONFIRMED` ou `CANCELLED` num `ConcurrentHashMap`.
- **Compensação:** o Checkout registra a reserva por UUID antes da publicação. Produto e quantidade recebidos precisam corresponder à reserva. Um bloqueio por reserva protege a devolução e o registro `COMPLETED`, inclusive quando cancelamento e falha de publicação concorrem. Reserva desconhecida ou divergente retorna **400**. Falha de execução não marca conclusão.
- **Idempotência:** o Processor ignora novos eventos com o mesmo `pedido_id`. A compensação só fica `COMPLETED` no Processor após resposta **204** do Checkout; falha de entrega mantém `PENDING`. Nova tentativa é explícita, pelo endpoint interno abaixo. Não há retry infinito automático.

Os pacotes `api`, `application`, `domain`, `ports` e `infrastructure` separam transporte, regras e armazenamento. As portas `OrderEventPublisher`, `OrderEventQueue`, `OrderRepository` e `OrderCompensationPublisher` permitem substituir os adaptadores. Cada processo tem sua própria memória; apenas JSON atravessa o HTTP. Os contratos dos dois eventos permanecem em snake_case, sem campos artificiais. `cliente_nome` continua opcional, como no Checkout original.

HTTP foi escolhido para demonstrar a comunicação entre processos sem instalar um broker. Utilizamos o [REST Client nativo do Quarkus](https://quarkus.io/guides/rest-client/) com URLs e timeouts em `application.properties`. As filas lógicas continuam sendo **order-created** e **order-compensation**.

## Executar no terminal ou IntelliJ

Configure `JAVA_HOME` para um JDK 21 e confirme `mvn -version` (ele pode usar um Java diferente de `java -version`). Maven instalado é suficiente; o wrapper original também está disponível no Checkout.

Na raiz do repositório, em dois terminais:

```bash
# Terminal 1
mvn -f order-processor-service/pom.xml quarkus:dev
```

```bash
# Terminal 2
mvn -f checkout-service/pom.xml quarkus:dev
```

No IntelliJ, adicione os dois `pom.xml` como projetos Maven. Selecione JDK 21 no Project SDK e no Maven Runner. Crie duas configurações Maven, cada uma com o diretório do respectivo serviço e comando `quarkus:dev`; execute ambas. Para simular falha, acrescente `-Dprocessor.mock.failure-mode=always` à configuração do Processor.

| Propriedade | Padrão |
| --- | --- |
| `quarkus.http.port` Checkout / Processor | `8080` / `8081` |
| `checkout.events.transport` | `http` (`memory` no perfil test) |
| `quarkus.rest-client.processor.url` | `http://localhost:8081` |
| `quarkus.rest-client.checkout.url` | `http://localhost:8080` |
| `processor.mock.failure-mode` | `never` (alternativa: `always`) |
| `processor.mock.queue-capacity` | `1000` |
| Timeouts dos clientes: connect / read | `1000` / `3000` ms |

Esses adaptadores são exclusivamente para demonstração local. As consultas de diagnóstico e o retry só são incluídos nos perfis dev/test. Não são a API pública de consulta da seção 4.2. Não há autenticação: não exponha este MVP como serviço de produção.

## Demonstrar sucesso

O estoque inicial é `PROD-001 = 10`, `PROD-002 = 0`. Cada reinício do Checkout reinicializa estoque e reservas.

```bash
curl -s http://localhost:8080/internal/mock/stock/PROD-001
curl -i http://localhost:8080/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Fernando Augusto"}'

# Copie o pedido_id da resposta 202:
PEDIDO_ID='cole-o-uuid-aqui'
curl -s "http://localhost:8081/internal/mock/orders/$PEDIDO_ID"
curl -s http://localhost:8080/internal/mock/stock/PROD-001
```

A resposta pública é `PROCESSING`. Na consulta interna, aguarde `CONFIRMED`, histórico `["PROCESSING","CONFIRMED"]`, compensação `NOT_REQUIRED` e estoque `8`. Uma consulta imediata pode retornar `404` ou `PROCESSING`: o processamento é assíncrono. Nenhuma compensação é publicada no sucesso.

## Demonstrar falha e compensação

Encerre e reinicie somente o Processor com:

```bash
mvn -f order-processor-service/pom.xml quarkus:dev -Dprocessor.mock.failure-mode=always
```

Repita o POST de criação acima e atualize `PEDIDO_ID`. Consulte:

```bash
curl -s "http://localhost:8081/internal/mock/orders/$PEDIDO_ID"
curl -s "http://localhost:8080/internal/mock/reservations/$PEDIDO_ID"
curl -s http://localhost:8080/internal/mock/stock/PROD-001
```

O pedido fica `CANCELLED`, histórico `["PROCESSING","CANCELLED"]`, compensação `COMPLETED`; o saldo volta ao valor anterior a esse pedido. Se o sucesso anterior consumiu duas unidades, retorna a `8`, não a `10`.

Para demonstrar idempotência, envie novamente a compensação (estoque deve permanecer igual):

```bash
curl -i http://localhost:8080/internal/mock/messages/order-compensation \
  -H 'Content-Type: application/json' \
  -d "{\"pedido_id\":\"$PEDIDO_ID\",\"produto_id\":\"PROD-001\",\"quantidade\":2,\"motivo\":\"Falha no processamento do pedido\"}"
```

Se a entrega falhar, a consulta mostra `PENDING`. Após recuperar o destino sem perder as reservas, solicite:

```bash
curl -i -X POST "http://localhost:8081/internal/mock/orders/$PEDIDO_ID/retry-compensation"
```

Retorna `204` se concluída/já concluída ou `503` se continua pendente. Não reinicie o Processor antes do retry: a pendência é volátil. O teste ponta a ponta abaixo simula indisponibilidade temporária sem reiniciar o Checkout. Logs incluem `pedido_id` e distinguem falha de processamento, comunicação e rejeição/erro HTTP no Checkout; não registram CPF.

## Testes e build

```bash
mvn -f checkout-service/pom.xml test
mvn -f order-processor-service/pom.xml test

# Teste HTTP real com duas JVMs: Python 3, somente biblioteca padrão.
# Perfil test inclui os endpoints de inspeção no artefato.
mvn -f checkout-service/pom.xml package -DskipTests -Dquarkus.profile=test
mvn -f order-processor-service/pom.xml package -DskipTests -Dquarkus.profile=test
python3 scripts/test-local-flow.py
```

O script escolhe portas locais livres, inicia/encerra as JVMs e testa sucesso, indisponibilidade do Processor, cancelamento, compensação duplicada e recuperação de entrega com retry. Um proxy HTTP local controlado pelo próprio teste simula falha da entrega de compensação. As verificações aguardam condições com limites explícitos de tempo.

JUnit 5/Quarkus Test/Mockito cobrem contratos, validação, fila limitada, transições, duplicidade concorrente, falhas, reserva sem overselling, devolução idempotente e corrida entre compensação e falha de publicação. Os dez testes originais do Checkout foram preservados.

## Limitações e evolução

- Tudo é volátil: fila, pedidos, histórico, reservas e registros de idempotência desaparecem ao reiniciar. O repositório cresce sem expiração; há apenas um consumidor. Não há coordenação entre réplicas.
- **HTTP não oferece as garantias de entrega e durabilidade de um broker.** Um `202` significa aceitação na memória, não persistência durável. Não há outbox, acknowledgements de broker, DLQ nem recuperação automática após crash.
- Uma resposta HTTP perdida pode deixar o resultado ambíguo: o Processor pode confirmar um pedido enquanto o Checkout, observando timeout, devolve a reserva. A idempotência evita devolução duplicada, mas não resolve essa janela distribuída. Não é uma solução de consistência para produção.
- Reiniciar o Checkout perde reservas e reinicia saldo mesmo que o Processor ainda tenha pedidos. Uma compensação de reserva perdida será rejeitada. Reinicie os dois para começar uma demonstração limpa.
- Falhas inesperadas do consumidor são registradas, sem reentrega durável. A simulação `always` ocorre após registrar `PROCESSING`; o repositório em memória não simula indisponibilidade de um banco real.
- Não foram implementados pagamento, autenticação, JPA, Cache-Aside ou `GET /v1/orders/{pedido_id}` público.

Próximos passos: implementar `StockRepository` com Redis e reserva/devolução atômicas, persistir também reservas e idempotência; substituir os publicadores HTTP e a fila em memória por produtores/consumidores RabbitMQ mantendo os contratos `order-created`/`order-compensation`. Definir publisher confirms, ack após processamento, retry limitado e DLQ; tratar a janela reserva/publicação com persistência e reconciliação/outbox apropriadas. Substituir `OrderRepository` por armazenamento durável antes de depender de recuperação após reinicialização. Cache-Aside fica para a seção 4.2.
