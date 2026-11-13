#!/usr/bin/env bash
# Starts a process over the engine's REST API.
#   scripts/start.sh <definitionId> <businessKey> '<variables as JSON>'
# e.g.
#   scripts/start.sh rooms-booking BOOKING-2 '{"guest": "Alice", "rooms": [{"number": "101", "type": "double"}]}'
set -euo pipefail
curl -s -X POST "${ENGINE:-http://localhost:8095}/workflow/api/processes" \
  -H 'Content-Type: application/json' \
  -d "{\"definitionId\": \"$1\", \"businessKey\": \"$2\", \"variables\": ${3:-{\}}}"
echo
