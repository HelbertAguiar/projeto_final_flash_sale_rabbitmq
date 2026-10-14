#!/usr/bin/env bash
# Inicialização apenas: não envia pedidos nem executa testes.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mode="${1:-}"
service="${2:-}"
case "$mode" in
  real)
    export FLASHSALE_DEMO_ENABLED=true PROCESSOR_FAILURE_MODE=never
    docker compose up -d --build --wait --wait-timeout 180
    docker compose ps
    ;;
  mock)
    case "$service" in checkout-service|order-processor-service) ;; *) echo 'Informe checkout-service ou order-processor-service' >&2; exit 2;; esac
    if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
    java -version 2>&1 | grep -q 'version "21[.]' || { echo 'Configure JAVA_HOME com JDK 21' >&2; exit 1; }
    mvn -version | grep -q 'Java version: 21[.]' || { echo 'Maven deve usar JDK 21' >&2; exit 1; }
    mvn -B -ntp -f "$service/pom.xml" package -DskipTests -Dquarkus.profile=mock
    exec java -Dquarkus.profile=mock -Dflashsale.real=false -Dflashsale.demo.enabled=true \
      -Dprocessor.mock.failure-mode=never -Dcheckout.events.transport=http \
      -jar "$service/target/quarkus-app/quarkus-run.jar"
    ;;
  *) echo 'Uso: bash scripts/start.sh real | mock <checkout-service|order-processor-service>' >&2; exit 2;;
esac
