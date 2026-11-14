#!/usr/bin/env bash
# Starts a process over the engine's REST API.
#   scripts/start.sh <definitionId> <businessKey> '<variables as JSON>'
# e.g.
#   scripts/start.sh airport-transfer TRANSFER-2 '{"guest": "Alice", "flight": "IB3456"}'
set -euo pipefail
OUT=$(curl -s -w '\n%{http_code}' -X POST "${ENGINE:-http://localhost:8096}/workflow/api/processes" \
  -H 'Content-Type: application/json' \
  -d "{\"definitionId\": \"$1\", \"businessKey\": \"$2\", \"variables\": ${3:-{\}}}")
echo "${OUT%$'\n'*}"
case "${OUT##*$'\n'}" in 2*) ;; *) exit 1 ;; esac
