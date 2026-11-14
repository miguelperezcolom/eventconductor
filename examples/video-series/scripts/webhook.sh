#!/usr/bin/env bash
# Plays the channel manager: POSTs a signed webhook (a booking created) to the app's generic webhook endpoint,
# /webhooks/channel-manager. The gateway checks X-Signature-256 (HMAC-SHA256 of the body, hex) and hands the
# event to the engine, where the on-booking-created trigger (events.yaml) starts booking-onboarding.
#   scripts/webhook.sh <bookingId> <guest> <checkIn>
# e.g.
#   scripts/webhook.sh CM-1001 Alice 2026-12-20
set -euo pipefail
SECRET="${CHANNEL_MANAGER_WEBHOOK_SECRET:-series-demo-secret}"
BODY="{\"bookingId\": \"$1\", \"guest\": \"$2\", \"checkIn\": \"$3\"}"
SIGNATURE=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -s -X POST "${ENGINE:-http://localhost:8096}/webhooks/channel-manager" \
  -H 'Content-Type: application/json' \
  -H "CE-Id: channel-manager-$1" \
  -H "X-Signature-256: sha256=$SIGNATURE" \
  -d "$BODY"
echo
