# Flash Sale

Projeto final de Java III — Helbert Oscar de Aguiar Silva, turma #1750, Adatech.


## Arquitetura

- Checkout: API pública, com banco acoplado do PostgreSQL, reserva atômica/idempotente no Redis e publicação pelo pattern outbox.
- Order Processor: consome RabbitMQ, persiste CONFIRMED/CANCELLED no próprio PostgreSQL e grava a compensação no outbox da mesma transação.
- RabbitMQ: filas `order-created` e `order-compensation`, mensagens persistentes, publisher confirms, ack, retry e DLQ. Os workers usam o cliente Java AMQP e polling `basicGet` a cada segundo.
- Redis: estoque/reservas e cache de pedidos confirmados com TTL de 300 segundos. A consulta usa a API interna do Processor em caso de MISS.

`POST /v1/orders` retorna `202 Accepted`, UUID e `PROCESSING`; `GET /v1/orders/{id}` acompanha o resultado e informa `X-Cache: MISS|HIT`. 202 significa aceitação para processamento assíncrono. 

Contrato e requisitos definidos em: [especificação](especificacao-projeto-final.md).

## Pré-requisitos

- JDK 21 e Maven 3.9+ para compilação e execução local. **Confira Java 21 tanto em `java -version` quanto em `mvn -version`.**
- Docker Engine/Compose no Linux ou Docker Desktop com containers Linux no Windows. Compose deve suportar `up --wait`; daemon iniciado e acessível.
- Bash/curl no Linux; PowerShell 5.1/7 e curl.exe no Windows.

Se necessário, configure `JAVA_HOME` com seu JDK 21 e acrescente seu diretório bin ao PATH.

Dockerfiles já compilam com Maven/JDK 21 e executam com JRE 21.

## Executar com Docker Compose

Na raiz, opcionalmente copie [.env.example](.env.example) para `.env` sem sobrescrever um arquivo existente.

Bash:
```bash
[ -f .env ] || cp .env.example .env
bash scripts/start.sh real
```

PowerShell:
```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode real
```

Os launchers compilam/iniciam e habilitam os controles locais de demonstração; não enviam compras nem executam testes. O Compose inicializa PROD-001=10 e PROD-002=0 somente se as chaves não existirem. Reiniciar não repõe estoque consumido.

| Serviço | Endereço padrão |
|---|---|
| Checkout | http://localhost:8080 |
| Order Processor | http://localhost:8081 |
| RabbitMQ Management | http://localhost:15672 ( credenciais definidas em .env, default: `flashsale` / `flashsale`, vhost `/flashsale`) |
| Redis | localhost:6379 |
| PostgreSQL Checkout | localhost:5433, banco/usuário/senha `checkout` |
| PostgreSQL Processor | localhost:5434, banco/usuário/senha `processor` |

Verifique (PowerShell: use curl.exe):
```bash
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8081/health/ready
docker compose ps
```
Esperado: HTTP 200, `status=UP`, `mode=real`. Sem as dependências, a prontidão retorna 503/DOWN; não muda para memória.

Encerre preservando volumes:
```bash
docker compose down
```

## Testes e demonstrações

O roteiro completo de demonstração dos requisitos definidos na [especificação do projeto](especificacao-projeto-final.md) está em [docs/demonstracao.md](docs/demonstracao.md). Ele orienta a validação manual do fluxo de sucesso, do cache-aside e da SAGA de compensação no ambiente real.

Testes Java e empacotamento (sem dependencia externa):
```bash
mvn -B -ntp -f checkout-service/pom.xml clean verify
mvn -B -ntp -f order-processor-service/pom.xml clean verify
```
Não há POM agregador na raiz.

## Demais considerações

Os controles `/internal/demo/*` permanecem porque demonstram as integrações reais. São locais, sem autenticação, e ficam desativados por padrão (`flashsale.demo.enabled=false`). O launcher os habilita explicitamente.

Somente CONFIRMED entra no cache. `compensacao=PUBLISHED` significa publicação no RabbitMQ, não estoque devolvido: confira a reserva Redis com status COMPLETED e o saldo. Falhas técnicas passam por retry/DLQ; cancelamentos de negócio geram compensação. Cada POST gera outro UUID, sem chave de idempotência do cliente.
