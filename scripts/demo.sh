#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for tool in curl python3; do
  command -v "$tool" >/dev/null || { echo "Ferramenta necessária: $tool" >&2; exit 1; }
done
if [[ -f .env ]]; then set -a; source .env; set +a; fi
EXPECTED="${1:-CONFIRMED}"
BASE="http://localhost:${CHECKOUT_PORT:-8080}"
BODY='{"produto_id":"PROD-001","quantidade":1,"cliente_cpf":"12345678909","cliente_nome":"Demo"}'
response="$(curl --fail-with-body --silent --show-error --max-time 15 -H 'Content-Type: application/json' -d "$BODY" "$BASE/v1/orders")"
echo "$response"
id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["pedido_id"])' <<<"$response")"
for ((attempt=0; attempt<40; attempt++)); do
  response="$(curl --silent --show-error --max-time 5 "$BASE/v1/orders/$id")"
  status="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' <<<"$response")"
  if [[ "$status" == "$EXPECTED" ]]; then
    echo "$response"
    if [[ "$EXPECTED" == CANCELLED ]]; then
      if curl --silent --fail "$BASE/internal/mock/reservations/$id" >/dev/null; then
        target="$BASE/internal/mock/reservations/$id"
        for ((retry=0; retry<30; retry++)); do
          result="$(curl --fail --silent "$target")"
          if [[ "$result" == *COMPLETED* ]]; then echo "$result"; exit 0; fi
          sleep 1
        done
      else
        for ((retry=0; retry<30; retry++)); do
          result="$(docker compose exec -T redis redis-cli HGET "reservation:$id" status)"
          if [[ "$result" == COMPLETED ]]; then
            echo "Compensação concluída; estoque atual:"
            docker compose exec -T redis redis-cli GET stock:PROD-001
            exit 0
          fi
          sleep 1
        done
      fi
      echo "Pedido cancelado, mas compensação não concluiu no prazo." >&2; exit 1
    fi
    exit 0
  fi
  if [[ "$status" == CONFIRMED || "$status" == CANCELLED ]]; then
    echo "Esperado $EXPECTED, recebido $status. Confira o modo de falha do Processor." >&2; exit 1
  fi
  sleep 1
done
echo "Pedido $id não atingiu $EXPECTED no prazo; consulte os logs e as DLQs." >&2
exit 1
