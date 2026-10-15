# Flash Sale — versão com dependências mockadas

Projeto Java 21 / Quarkus 3.40.1. Esta branch, `versao-dependencias-mockadas`, preserva a versão didática a partir de `f247874`. A branch `main` evolui com Redis, RabbitMQ e PostgreSQL reais. Nenhuma branch precisa de arquivos da outra para executar.

## Arquitetura

Duas JVMs independentes: Checkout reserva estoque em memória e envia eventos por HTTP; Processor usa uma fila local e persiste pedidos em memória. A compensação volta por HTTP. Estoque, reservas, pedidos e cache são voláteis.

A API pública mantém `POST /v1/orders` (202, UUID e PROCESSING), `GET /v1/orders/{id}`, estados CONFIRMED/CANCELLED e `X-Cache: MISS|HIT`. O cache de confirmados tem TTL de 300 segundos. Dados iniciais: PROD-001=10 e PROD-002=0.

Os adaptadores reais e arquivos de infraestrutura herdados foram conservados para minimizar alterações de preservação. **Eles não são necessários à execução mockada e permanecem inativos nesse modo.** O roteiro desta branch não usa Docker, Redis, RabbitMQ nem PostgreSQL.

## Pré-requisitos

- JDK 21 e Maven 3.9+, ambos apontando para o mesmo JDK.
- Bash, curl e Python 3 (somente biblioteca padrão) para automações.
- PowerShell 5.1/7 e curl.exe para o roteiro manual no Windows.
- Internet na primeira resolução das dependências Maven; não há promessa de compilação offline com cache vazio.

Confira `java -version` e `mvn -version`. No Linux, configure `JAVA_HOME` com seu JDK 21 e acrescente `$JAVA_HOME/bin` ao PATH.

## Executar

Na raiz, inicie ambos os serviços:
```bash
bash scripts/run-mock.sh
```
Esse comando compila e inicia as duas JVMs. Ctrl+C encerra somente os processos iniciados pelo script; logs ficam em `.run/`. Ele lê o `.env` opcional; use portas livres.

Em outro terminal:
```bash
bash scripts/demo-mock.sh
```
A demonstração prepara estoque, confirma um pedido, verifica MISS/HIT e cancela outro com devolução. Pode ser repetida quando não houver pedidos pendentes.

Alternativamente, dois terminais independentes, sem Python:
```bash
bash scripts/start.sh mock order-processor-service
bash scripts/start.sh mock checkout-service
```
No Windows, um comando em cada terminal:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode mock -Service order-processor-service
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Mode mock -Service checkout-service
```
Os launchers start usam portas padrão e não carregam `.env`. Checkout: http://localhost:8080; Processor: http://localhost:8081. Consulte `/health/ready` em ambos: HTTP 200, `mode=mock`.

## Testes e demonstrações

```bash
mvn -B -ntp -f checkout-service/pom.xml clean verify
mvn -B -ntp -f order-processor-service/pom.xml clean verify
mvn -B -ntp -f checkout-service/pom.xml package -DskipTests -Dquarkus.profile=mock
mvn -B -ntp -f order-processor-service/pom.xml package -DskipTests -Dquarkus.profile=mock
python3 scripts/test-local-flow.py
```

O teste ponta a ponta inicia suas próprias JVMs em portas locais livres e as encerra ao terminar. Inclui indisponibilidade HTTP, duplicidade, compensação pendente/retry, concorrência e duas execuções da demonstração.

Para cancelamento de todas as compras, use `bash scripts/run-mock-failure.sh` e, em outro terminal, `bash scripts/demo-compensation.sh`. Para sucesso normal, use `bash scripts/run-mock.sh` e `bash scripts/demo-success.sh`.

Veja [roteiro manual](docs/TESTES.md), [limitações](README-MOCK.md), [Checkout](checkout-service/README.md) e [Processor](order-processor-service/README.md).

## Alternar versões

Encerre as JVMs antes de trocar:
```bash
git switch main
# Para voltar:
git switch versao-dependencias-mockadas
```
Recompile ao trocar de branch: os diretórios target são ignorados pelo Git. A possível exclusão futura desta branch não afeta a execução da main.

## Validação da preservação — 15/10/2026

Linux, JDK 21 e Maven 3.9.11: `clean verify` aprovado nos dois módulos (18 testes Checkout e 12 Processor, sem falhas). Empacotamento no perfil mock, teste ponta a ponta e inicialização por `run-mock.sh` aprovados. Demonstrações `demo-mock.sh` e `demo-success.sh` aprovadas; prontidão UP/mode=mock em ambas as JVMs. O teste ponta a ponta também executou o roteiro completo duas vezes e validou falhas/compensação/concorrência.

Não havia containers executando nem listeners nas portas locais de Redis, RabbitMQ e PostgreSQL utilizadas pelo projeto. O Failsafe está desabilitado no POM original; os testes Java existentes são executados pelo Surefire, e o ponta a ponta Python é executado separadamente. Windows/PowerShell não executado neste ambiente Linux.
