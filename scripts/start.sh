#!/usr/bin/env bash
# Inicialização apenas: não envia pedidos nem executa testes.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
case "${1:-real}" in
  real)
    export FLASHSALE_DEMO_ENABLED=true PROCESSOR_FAILURE_MODE=never
    docker compose up -d --build --wait --wait-timeout 180
    docker compose ps
    ;;
  *) echo 'Uso: bash scripts/start.sh real. A versão mockada está na branch versao-dependencias-mockadas.' >&2; exit 2;;
esac
