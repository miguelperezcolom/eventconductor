#!/usr/bin/env bash
# Sends a message to the processes waiting for it (POST /workflow/api/messages).
#   scripts/message.sh <messageName> <correlationKey> ['<variables as JSON>']
# e.g.
#   scripts/message.sh payment-received BALANCE-1 '{"paidAmount": "360"}'
set -euo pipefail
curl -s -X POST "${ENGINE:-http://localhost:8096}/workflow/api/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"messageName\": \"$1\", \"correlationKey\": \"$2\", \"variables\": ${3:-{\}}}"
echo
