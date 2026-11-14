#!/usr/bin/env bash
# Asks for a quote and waits for the answer: a synchronous invocation of room-quote. The REPLY step answers;
# the process carries on (it holds the quote for 15 minutes).
#   scripts/invoke.sh <businessKey> <roomType> <nights>
# e.g.
#   scripts/invoke.sh QUOTE-1 suite 3
set -euo pipefail
curl -s -X POST "${ENGINE:-http://localhost:8096}/workflow/api/definitions/room-quote/invocations" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: quote-$1" \
  -H 'Prefer: wait=10' \
  -d "{\"businessKey\": \"$1\", \"variables\": {\"roomType\": \"$2\", \"nights\": \"$3\"}}"
echo
