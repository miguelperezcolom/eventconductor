#!/usr/bin/env bash
# The guest's answer to "some rooms could not be booked": what the hotel's website would send when the
# guest clicks a link in the email. Delivered as the message `guest-answer`, correlated by the booking's
# business key (POST /workflow/api/messages).
#   scripts/answer.sh <businessKey> <accept|keep|cancel>
# e.g.
#   scripts/answer.sh BOOKING-3 cancel
#
#   accept — book the alternative the hotel offered (modules 4 and 6)
#   keep   — keep the rooms that were booked, without the alternative
#   cancel — cancel the whole booking: the rooms that were booked are cancelled (compensated)
set -euo pipefail
curl -s -X POST "${ENGINE:-http://localhost:8095}/workflow/api/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"messageName\": \"guest-answer\", \"correlationKey\": \"$1\", \"variables\": {\"answer\": \"$2\"}}"
echo
