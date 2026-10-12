# Checkout Service — MVP da seção 4.1

Microsserviço Quarkus em Java 21 que aceita pedidos de uma Flash Sale. O endpoint `POST /v1/orders` valida os dados, reserva estoque de forma atômica, gera um UUID v4, publica um evento simulado na fila lógica `order-created` e responde `202 Accepted`.

## Organização

| Pacote | Papel |
| --- | --- |
| `api` | Endpoint REST, contratos JSON e conversão de exceções em respostas HTTP. |
| `application` | `CheckoutService` orquestra validação, reserva, publicação e devolução em caso de falha. |
| `domain` | Evento e exceções que expressam os resultados do negócio. |
| `ports` | Interfaces `StockRepository` e `OrderEventPublisher`, independentes de tecnologia. |
| `infrastructure` | Implementações em memória das duas interfaces. |

O serviço de aplicação depende das portas. Isso permite trocar os adaptadores sem alterar o fluxo de checkout.

## Dependências simuladas

- **Estoque:** `InMemoryStockRepository` inicia com `PROD-001 = 10` e `PROD-002 = 0`. Produtos desconhecidos têm saldo disponível zero. `ConcurrentHashMap.computeIfPresent` verifica e decrementa o saldo na mesma operação por produto, impedindo estoque negativo em chamadas concorrentes. A devolução também é atômica.
- **Mensageria:** `InMemoryOrderEventPublisher` guarda eventos em uma coleção segura para concorrência. `publishedEvents()` fornece uma cópia para inspeção nos testes; `failNextPublication()` força uma falha de publicação uma vez. Ao falhar, o serviço devolve a reserva, registra o erro sem CPF e responde `503`.

Os dados em memória são **temporários**, desaparecem ao reiniciar e não representam uma solução distribuída de produção. A publicação simula apenas a entrega local: não há broker, durabilidade, confirmação remota ou processamento do pedido.

## Executar localmente

Pré-requisitos: JDK 21 e Maven (ou o Maven Wrapper incluído). Na pasta `checkout-service`:

```bash
./mvnw quarkus:dev
```

O serviço fica em `http://localhost:8080`. Redis, RabbitMQ, PostgreSQL e Docker não são necessários. As extensões desses serviços ainda não fazem parte do Maven deste MVP. O [compose.yml](../compose.yml) da raiz não precisa ser iniciado.

## Requisições e respostas

```bash
curl -i -X POST http://localhost:8080/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Fernando Augusto"}'
```

Resposta `202 Accepted` (UUID e horário variam):

```json
{"pedido_id":"b819cc65-f6f0-478c-8bb7-69ee1c4f6402","status":"PROCESSING","data_hora_requisicao":"2026-09-07T20:03:57"}
```

Exemplo sem estoque:

```bash
curl -i -X POST http://localhost:8080/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"produto_id":"PROD-002","quantidade":1,"cliente_cpf":"50329291076","cliente_nome":"Fernando Augusto"}'
```

Resposta `409 Conflict`:

```json
{"mensagem":"Estoque insuficiente para o produto PROD-002"}
```

Campos inválidos retornam `400 Bad Request` com `mensagem` apontando `produto_id`, `quantidade` e/ou `cliente_cpf`. `cliente_nome` é preservado como recebido e não tem validação adicional. Falha de publicação retorna `503 Service Unavailable` com `{"mensagem":"Não foi possível publicar o pedido"}`.

O evento armazenado em memória segue o contrato:

```json
{"pedido_id":"b819cc65-f6f0-478c-8bb7-69ee1c4f6402","produto_id":"PROD-001","quantidade":2,"cliente_cpf":"50329291076","cliente_nome":"Fernando Augusto","data_hora_criacao":"2026-09-07T20:03:57"}
```

Não há endpoint público para inspecionar eventos; os testes os verificam diretamente pelo adaptador em memória.

## Testes e compilação

```bash
./mvnw test
./mvnw package -DskipTests
```

Os testes unitários usam Mockito para isolar o `CheckoutService`. Os testes com `@QuarkusTest` verificam contratos HTTP/JSON, estoque, publicação, falha e concorrência com os adaptadores em memória. O estado dos adaptadores é reiniciado antes de cada teste de integração.

## Evolução e limites

Para usar Redis futuramente, implemente `StockRepository` com consulta, reserva condicional atômica e devolução, por exemplo via script Lua. Para RabbitMQ, implemente `OrderEventPublisher` serializando `OrderCreatedEvent` na fila `order-created` e confirmando a publicação. Será necessário considerar falhas entre a reserva Redis e a confirmação no broker; a devolução local desta etapa não constitui transação distribuída.

Esta etapa não inclui consulta `GET /v1/orders/{pedido_id}`, consumidor de compensação, Order Processor, persistência nem integrações reais. Essas partes pertencem às próximas seções do projeto.
