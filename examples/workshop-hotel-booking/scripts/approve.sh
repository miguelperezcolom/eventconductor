#!/usr/bin/env bash
# Decides a booking's approval the way a manager does: through the forms engine's task API
# (/forms/api/tasks) — finds the open task of the booking, claims it, and submits the
# booking-approval form.
#   scripts/approve.sh <businessKey> <true|false> ['<comment>']
# e.g.
#   scripts/approve.sh BOOKING-7 true 'Regular guest'
#
# Acts as "mia", of the front-desk-managers group, with a token the app takes as it comes
# (workflow.security.trust-forwarded-token=true — a workshop shortcut for a login server).
set -euo pipefail
ENGINE="${ENGINE:-http://localhost:8095}"
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
TOKEN="$(printf '{"alg":"none","typ":"JWT"}' | b64).$(printf '{"sub":"mia","preferred_username":"mia","realm_access":{"roles":["front-desk-managers"]}}' | b64)."
AUTH="Authorization: Bearer $TOKEN"

TASK=$(curl -s "$ENGINE/forms/api/tasks?businessKey=$1" -H "$AUTH" \
  | sed -n 's/^{"items":\[{"id":"\([^"]*\)".*/\1/p')
if [ -z "$TASK" ]; then
  echo "No open approval task for $1 (is the process waiting at 'approve'?)" >&2
  exit 1
fi
curl -s -o /dev/null -X POST "$ENGINE/forms/api/tasks/$TASK/claim" -H "$AUTH"
curl -s -X POST "$ENGINE/forms/api/tasks/$TASK/complete" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"values\": {\"approved\": $2, \"comment\": \"${3:-}\"}}"
echo
