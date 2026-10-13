#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi
exec python3 scripts/demo-flow.py mock
