# Flash Sale — Java 21 e Quarkus

Projeto final de Java III — Helbert Oscar de Aguiar Silva, turma #1750, Adatech.

Dois microsserviços: Checkout recebe compras e reserva estoque; Order Processor processa os eventos e confirma ou cancela pedidos. `202 Accepted` significa aceitação para processamento assíncrono. Contrato e requisitos: [especificação](especificacao-projeto-final.md).

## Ambientes

| | MOCK | REAL |
|---|---|---|
| Execução proposta | Duas JVMs locais | Aplicações e dependências no Docker Compose |
| Estoque e cache | Mapas em memória | Redis 7.4, reserva atômica com Lua, cache de 300 s |
| Pedidos | Repositório em memória | PostgreSQL 17, um banco por serviço |
| Eventos | HTTP entre serviços e fila local em memória | RabbitMQ 4, filas `order-created` e `order-compensation` |
| Recuperação | Dados perdidos ao encerrar JVMs | Volumes, outboxes, ack, retry e DLQ |
| Evidência dos 12 requisitos | Apenas simulação funcional | Demonstração completa com infraestrutura real |

MOCK não comprova uso de Redis, RabbitMQ ou persistência durável. Detalhes e limites: [README-MOCK](README-MOCK.md). A arquitetura e os adaptadores existentes são preservados. O REAL usa cliente Java AMQP e JDBC; os workers consultam as filas a cada segundo (`basicGet`).

## Pré-requisitos

- Windows 10/11: PowerShell 5.1 ou 7, JDK 21, Maven 3.9+, Docker Desktop com containers Linux e WSL2 compatível com sua versão do Windows.
- Linux: Bash, JDK 21, Maven 3.9+, Docker Engine e plugin Compose.
- Docker Compose com `up --wait`; daemon iniciado e acesso permitido ao usuário.
- `curl.exe` no Windows; `curl` no Linux. Python não é necessário neste roteiro.
- Internet na primeira compilação/build. Quarkus 3.40.1 é resolvido pelo Maven, sem instalação de CLI.

Execute na raiz do repositório:

```text
java -version
mvn -version
docker compose version
```

**Java e Maven devem indicar Java 21.** Se necessário, ajuste para o caminho real do seu JDK:

PowerShell:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Bash:
```bash
export JAVA_HOME=/caminho/do/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
```

## Inicialização

Os novos launchers apenas compilam/iniciam; não executam testes nem enviam compras. Habilitam os controles locais de demonstração e processamento normal. Não execute MOCK e REAL simultaneamente nas mesmas portas.

### MOCK: dois terminais

PowerShell, terminal 1 e terminal 2, respectivamente:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode mock -Service order-processor-service
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode mock -Service checkout-service
```

Bash, terminal 1 e terminal 2, respectivamente:
```bash
bash scripts/start.sh mock order-processor-service
bash scripts/start.sh mock checkout-service
```

Aguarde cada aplicação iniciar. Os logs ficam no próprio terminal; Ctrl+C encerra a JVM correspondente. Os scripts MOCK usam as portas padrão das aplicações e não carregam `.env`; variáveis Quarkus já exportadas podem alterar sua configuração.

### REAL: Docker Compose

Opcionalmente copie [.env.example](.env.example) para `.env` se ainda não existir. O Compose lê esse arquivo; mantenha os valores padrão para seguir o roteiro.

PowerShell:
```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode real
```

Bash:
```bash
[ -f .env ] || cp .env.example .env
bash scripts/start.sh real
```

Os Dockerfiles compilam com Maven/JDK 21 e executam com JRE 21. O Compose espera os healthchecks e inicializa `PROD-001=10`, `PROD-002=0` somente quando as chaves não existem. Reiniciar não repõe estoque consumido. Os controles `/internal/demo/*` são para uso local dedicado e não possuem autenticação.

| Serviço | Endereço padrão |
|---|---|
| Checkout | http://localhost:8080 |
| Order Processor | http://localhost:8081 |
| RabbitMQ Management | http://localhost:15672 (`flashsale` / `flashsale`, vhost `/flashsale`) |
| Redis | localhost:6379 |
| PostgreSQL Checkout | localhost:5433, banco/usuário/senha `checkout` |
| PostgreSQL Processor | localhost:5434, banco/usuário/senha `processor` |

Verificação de prontidão (PowerShell: use `curl.exe`; Bash: use `curl`):
```bash
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8081/health/ready
```

Esperado: HTTP 200, `status=UP` e `mode=mock` ou `real`. No REAL:
```text
docker compose ps
docker compose logs --tail 50 checkout-service order-processor-service
```

Encerrar REAL, preservando volumes:
```text
docker compose down
```

## Demonstração e compilação

Siga [docs/TESTES.md](docs/TESTES.md): 12 etapas manuais, com inspeção individual de filas, logs, PostgreSQL e Redis, em PowerShell ou Bash. Não execute os scripts legados `demo-*`/`test-*-flow.py` durante essa apresentação: são automações anteriores, preservadas, mas não fazem parte do roteiro.

Compilação local, em ambos os shells (sem execução de testes):
```text
mvn -B -ntp -f checkout-service/pom.xml package -DskipTests
mvn -B -ntp -f order-processor-service/pom.xml package -DskipTests
```

Os testes Java preexistentes continuam disponíveis via `mvn -f <serviço>/pom.xml test`; não substituem a observação manual da infraestrutura. Não há POM agregador na raiz.

## Limites do fluxo

Somente pedidos `CONFIRMED` entram no cache. No cancelamento, o Processor persiste `CANCELLED` e a outbox de compensação na mesma transação; a devolução ocorre depois no Checkout. `compensacao=PUBLISHED` indica publicação no broker, não estoque devolvido: verifique `reservation:UUID` com `status=COMPLETED` e o saldo. Mensagens com falha técnica passam por retry e podem chegar à DLQ; a falha controlada do roteiro é uma decisão de negócio e gera compensação. Cada POST cria outro UUID, sem chave de idempotência do cliente.

## Validação realizada em 14/10/2026

- Linux: os dois módulos empacotados com Maven 3.9.11 e JDK 21; testes não executados (`-DskipTests`).
- MOCK: inicialização pelos novos scripts Bash e prontidão `UP`, `mode=mock`, nos dois serviços.
- REAL: build das imagens com camadas em cache e `compose up --wait` concluídos; aplicações, Redis, RabbitMQ e os dois PostgreSQL saudáveis.
- Sintaxe Bash e revisão de whitespace verificadas. Windows/PowerShell não executado: não há Windows nem PowerShell instalado neste ambiente.
- Os 12 passos estão documentados para execução manual; não foram executados como demonstração automática nem certificados integralmente nesta validação de inicialização.

Foi necessário liberar acesso ao Maven Central e ao daemon Docker fora do sandbox. Um conflito de porta durante a validação foi resolvido encerrando o MOCK antes de iniciar o REAL.
