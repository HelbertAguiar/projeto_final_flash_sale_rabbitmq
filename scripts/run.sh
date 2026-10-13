#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
usage() {
  cat <<'HELP'
Uso: bash scripts/run.sh <mock|mock-failure|infra|real-local|docker|docker-failure|stop>
  mock          Duas JVMs, HTTP e memória; não requer Docker.
  mock-failure  Mesmo modo, simulando cancelamento no Processor.
  infra         Redis, RabbitMQ e dois PostgreSQL em Docker + estoque inicial.
  real-local    Dependências em Docker e duas JVMs locais no perfil real.
  docker        Todo o sistema em Docker, processamento normal.
  docker-failure Todo o sistema em Docker, simulando cancelamento.
  stop          Encerra os containers, preservando todos os volumes.
Ctrl+C encerra somente as JVMs iniciadas pelo script; containers continuam disponíveis.
Configuração opcional: .env (veja .env.example). Logs locais: .run/.
HELP
}
MODE="${1:-help}"
case "$MODE" in mock|mock-failure|infra|real-local|docker|docker-failure|stop) ;; help|--help|-h) usage; exit 0;; *) usage; exit 2;; esac
if [[ -f .env ]]; then set -a; source .env; set +a; fi
need() { command -v "$1" >/dev/null || { echo "Ferramenta necessária: $1" >&2; exit 1; }; }
compose() { docker compose "$@"; }
if [[ "$MODE" != mock && "$MODE" != mock-failure ]]; then
  need docker
  docker compose version >/dev/null
  docker info >/dev/null
fi
case "$MODE" in
  stop) compose down; exit;;
  docker|docker-failure)
    if [[ "$MODE" == docker-failure ]]; then export PROCESSOR_FAILURE_MODE=always; else export PROCESSOR_FAILURE_MODE=never; fi
    compose up -d --build --wait --wait-timeout 180
    echo "Checkout: http://localhost:${CHECKOUT_PORT:-8080}"
    echo "RabbitMQ: http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}"
    compose ps
    exit;;
  infra|real-local)
    compose up -d --wait --wait-timeout 180 redis rabbitmq checkout-db processor-db
    compose run --rm redis-seed
    if [[ "$MODE" == infra ]]; then compose ps; exit; fi;;
esac

export FLASHSALE_DEMO_ENABLED=${FLASHSALE_DEMO_ENABLED:-true}
need mvn
need curl
need python3
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
  export PATH="$JAVA_HOME/bin:$PATH"
else
  JAVA="$(command -v java)"
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA")")")"
fi
if ! "$JAVA" -version 2>&1 | head -1 | grep -q '"21[.]'; then
  echo 'Este projeto requer JDK 21; ajuste JAVA_HOME.' >&2
  exit 1
fi
"$JAVA" -version
mvn -version
# Detecta conflito antes de iniciar qualquer JVM.
python3 - "${CHECKOUT_PORT:-8080}" "${PROCESSOR_PORT:-8081}" <<'PY'
import socket, sys
ports = list(map(int, sys.argv[1:]))
if len(set(ports)) != 2:
    sys.exit("As portas dos dois serviços devem ser diferentes")
for port in ports:
    with socket.socket() as sock:
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            sys.exit(f"Porta {port} ocupada. Encerre a outra execução ou ajuste .env.")
PY
if [[ "$MODE" == real-local ]]; then
  BUILD_PROFILE=prod
  RUNTIME_PROFILE=real
  export QUARKUS_REDIS_HOSTS="redis://localhost:${REDIS_PORT:-6379}"
  export RABBITMQ_HOST=localhost
  export RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
else
  BUILD_PROFILE=mock
  RUNTIME_PROFILE=mock
fi
# O perfil mock não depende do perfil de testes.
mvn -B -ntp -f checkout-service/pom.xml package -DskipTests "-Dquarkus.profile=$BUILD_PROFILE"
mvn -B -ntp -f order-processor-service/pom.xml package -DskipTests "-Dquarkus.profile=$BUILD_PROFILE"
mkdir -p .run
pids=()
cleanup() {
  trap - EXIT INT TERM
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
FAILURE="${PROCESSOR_FAILURE_MODE:-never}"
if [[ "$MODE" == mock-failure ]]; then FAILURE=always; fi
"$JAVA" "-Dquarkus.profile=$RUNTIME_PROFILE" "-Dquarkus.http.port=${PROCESSOR_PORT:-8081}" \
  "-Dprocessor.mock.failure-mode=$FAILURE" \
  "-Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:${PROCESSOR_DB_PORT:-5434}/processor" \
  "-Dquarkus.datasource.password=${PROCESSOR_DB_PASSWORD:-processor}" \
  "-Dquarkus.rest-client.checkout.url=http://localhost:${CHECKOUT_PORT:-8080}" \
  -jar order-processor-service/target/quarkus-app/quarkus-run.jar >.run/processor.log 2>&1 &
pids+=("$!")
"$JAVA" "-Dquarkus.profile=$RUNTIME_PROFILE" "-Dquarkus.http.port=${CHECKOUT_PORT:-8080}" \
  -Dcheckout.events.transport=http \
  "-Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:${CHECKOUT_DB_PORT:-5433}/checkout" \
  "-Dquarkus.datasource.password=${CHECKOUT_DB_PASSWORD:-checkout}" \
  "-Dquarkus.rest-client.processor.url=http://localhost:${PROCESSOR_PORT:-8081}" \
  -jar checkout-service/target/quarkus-app/quarkus-run.jar >.run/checkout.log 2>&1 &
pids+=("$!")
for port in "${PROCESSOR_PORT:-8081}" "${CHECKOUT_PORT:-8080}"; do
  ready=false
  for ((attempt=0; attempt<90; attempt++)); do
    for pid in "${pids[@]}"; do
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "Uma JVM encerrou. Consulte .run/checkout.log e .run/processor.log." >&2
        exit 1
      fi
    done
    if curl --fail --silent --max-time 2 "http://localhost:$port/health/ready" >/dev/null; then ready=true; break; fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then echo "Timeout aguardando porta $port; consulte .run/." >&2; exit 1; fi
done
echo "Modo $MODE pronto. Checkout: http://localhost:${CHECKOUT_PORT:-8080}"
echo "Logs: .run/checkout.log e .run/processor.log. Ctrl+C encerra as duas JVMs."
wait -n "${pids[@]}"
