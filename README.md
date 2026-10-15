# Flash Sale — Java 21 e Quarkus

Projeto final de Java III — Helbert Oscar de Aguiar Silva, turma #1750, Adatech.


## Arquitetura

- Checkout: API pública, intenção durável no PostgreSQL, reserva atômica/idempotente no Redis e publicação pelo outbox.
- Order Processor: consome RabbitMQ, persiste CONFIRMED/CANCELLED no próprio PostgreSQL e grava a compensação no outbox da mesma transação.
- RabbitMQ: filas `order-created` e `order-compensation`, mensagens persistentes, publisher confirms, ack, retry e DLQ. Os workers usam o cliente Java AMQP e polling `basicGet` a cada segundo.
- Redis: estoque/reservas e cache de pedidos confirmados com TTL de 300 segundos. A consulta usa a API interna do Processor em caso de MISS.

`POST /v1/orders` retorna `202 Accepted`, UUID e `PROCESSING`; `GET /v1/orders/{id}` acompanha o resultado e informa `X-Cache: MISS|HIT`. 202 significa aceitação para processamento assíncrono. Contrato e requisitos: [especificação](especificacao-projeto-final.md).

Não há seleção de execução HTTP/mock ou fallback para memória. O perfil `real` continua aceito pelos comandos existentes; os perfis padrão/dev também usam infraestrutura real. Mockito e a desativação de recursos nos testes ficam somente no escopo de teste.

## Pré-requisitos

- JDK 21 e Maven 3.9+ para compilação e execução local. **Confira Java 21 tanto em `java -version` quanto em `mvn -version`.**
- Docker Engine/Compose no Linux ou Docker Desktop com containers Linux no Windows. Compose deve suportar `up --wait`; daemon iniciado e acessível.
- Bash/curl no Linux; PowerShell 5.1/7 e curl.exe no Windows.
- Python 3, somente biblioteca padrão, para automações opcionais. O teste real automatizado usa recursos Linux (`/dev/null`).
- Internet para resolver dependências Maven e imagens ainda não disponíveis localmente. Quarkus 3.40.1 é resolvido pelo Maven.

Se necessário, configure `JAVA_HOME` com seu JDK 21 e acrescente seu diretório bin ao PATH. Dockerfiles já compilam com Maven/JDK 21 e executam com JRE 21.

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
| RabbitMQ Management | http://localhost:15672 (`flashsale` / `flashsale`, vhost `/flashsale`) |
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

## Execução local com infraestrutura real

```bash
bash scripts/run-real-local.sh
```
Esse comando inicia a infraestrutura em Docker, compila os módulos e inicia duas JVMs Java 21. Lê `.env`; logs ficam em `.run/`. Ctrl+C encerra somente as JVMs iniciadas pelo script; encerre a infraestrutura separadamente. Não use as mesmas portas de uma execução completa já ativa.

Para desenvolvimento por módulo, inicie `bash scripts/run-infrastructure.sh` e siga [Checkout](checkout-service/README.md) e [Processor](order-processor-service/README.md).

## Testes e demonstrações

Testes Java e empacotamento (sem infraestrutura externa):
```bash
mvn -B -ntp -f checkout-service/pom.xml clean verify
mvn -B -ntp -f order-processor-service/pom.xml clean verify
```
Não há POM agregador na raiz. Testes isolados usam Mockito nas fronteiras de infraestrutura e exercitam o código real. O Failsafe permanece desabilitado como no projeto original; os testes Java são executados pelo Surefire. Isso não substitui a integração abaixo.

Integração real completa, sem precisar iniciar o ambiente normal:
```bash
python3 scripts/test-real-flow.py
```
O script cria projeto Compose com nome e portas próprios, ignora o `.env` local e remove somente os containers/volumes que criou. Inclui demonstração, contratos, persistência, indisponibilidade e recuperação, concorrência, duplicidade, compensação e DLQ.

No ambiente normal já iniciado pelo launcher:
```bash
bash scripts/demo-real.sh
```
Esse roteiro modifica o estoque de demonstração: use ambiente dedicado, sem tráfego concorrente. Para inspecionar individualmente filas, bancos e Redis, siga [os 12 passos manuais](docs/TESTES.md).

## Falha controlada e limites

Os controles `/internal/demo/*` permanecem porque demonstram as integrações reais. São locais, sem autenticação, e ficam desativados por padrão (`flashsale.demo.enabled=false`). O launcher os habilita explicitamente.

`processor.failure-mode=never|always` controla o cancelamento didático. No Compose, use `PROCESSOR_FAILURE_MODE`; `bash scripts/run.sh docker-failure` força cancelamentos e `bash scripts/run-docker.sh` restaura o processamento normal. A propriedade antiga `processor.mock.failure-mode` continua como alias quando a nova não é definida, para compatibilidade. Essa simulação de decisão de negócio não substitui infraestrutura.

Somente CONFIRMED entra no cache. `compensacao=PUBLISHED` significa publicação no RabbitMQ, não estoque devolvido: confira a reserva Redis com status COMPLETED e o saldo. Falhas técnicas passam por retry/DLQ; cancelamentos de negócio geram compensação. Cada POST gera outro UUID, sem chave de idempotência do cliente.

## Alternar versões

Encerre o ambiente atual antes de trocar, para evitar portas compartilhadas:
```bash
git switch versao-dependencias-mockadas
bash scripts/run-mock.sh
# Após encerrar as JVMs:
git switch main
bash scripts/start.sh real
```
Recompile ao alternar: os diretórios target não são versionados. O perfil `mock` antigo não seleciona mais dependências em memória nesta branch; use a branch didática.

Veja [inventário e validação da separação](docs/SEGREGACAO.md).
