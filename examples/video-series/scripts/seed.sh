#!/usr/bin/env bash
# Fills the console with a realistic mix of processes, so no screen of the series is empty: some waiting
# (an approval, a timer, a payment, a room), some completed, one compensated, and three stopped on an incident.
# Run it once against a freshly started app (business keys are unique: a second run collapses into the first).
#   scripts/seed.sh
set -euo pipefail
cd "$(dirname "$0")"
ENGINE="${ENGINE:-http://localhost:8096}"
export ENGINE
start() { ./start.sh "$@" > /dev/null && echo "  started $1 $2" || { echo "  could not start $1 $2" >&2; exit 1; }; }
# Waits until the process holding <businessKey> has reached <stepId> (armed, running or done).
reached() {
  for _ in $(seq 1 40); do
    local id
    id=$(curl -s "$ENGINE/workflow/api/processes?businessKey=$1" | sed -n 's/.*"content":\[{"id":"\([^"]*\)".*/\1/p')
    if [ -n "$id" ] && curl -s "$ENGINE/workflow/api/processes/$id/steps" \
        | grep -q "\"stepId\":\"$2\",\"status\":\"\(PENDING\|RUNNING\|COMPLETED\)\""; then
      return 0
    fi
    sleep 0.5
  done
  echo "  $1 did not reach $2" >&2
}
# Waits until <n> processes of <definitionId> are in <status>.
count_is() {
  for _ in $(seq 1 60); do
    [ "$(curl -s "$ENGINE/workflow/api/processes?definitionId=$1&status=$2&size=100" | grep -o '"businessKey"' | wc -l | tr -d ' ')" -ge "$3" ] && return 0
    sleep 1
  done
}
day() { date -v+"$1"d +%F 2>/dev/null || date -d "+$1 day" +%F; }   # macOS or GNU date

# The definitions are imported once the app is up (after "Started ..."): wait for the last thing imported.
for _ in $(seq 1 60); do
  curl -sf "$ENGINE/workflow/api/triggers/booking-onboarding/on-booking-created" > /dev/null && break
  sleep 1
done

echo "Waiting processes"
start hotel-booking BOOKING-7 '{"guest": "Grace", "rooms": [{"number": "101", "type": "double"}, {"number": "102", "type": "double"}, {"number": "103", "type": "double"}]}'
start balance-reminder BALANCE-1 "{\"guest\": \"Carol\", \"checkIn\": \"$(day 20)\", \"balance\": \"360\"}"
start balance-reminder BALANCE-2 "{\"guest\": \"Dan\", \"checkIn\": \"$(day 2)\", \"balance\": \"240\"}"   # past "3 days before": waits for the payment
start guest-checkin CHECKIN-1 '{"guest": "Bob", "room": "305"}'
start loyalty-statement LOYALTY-1 '{"member": "M-7", "month": "11", "points": "1320"}'
./invoke.sh QUOTE-1 suite 3 > /dev/null && echo "  invoked room-quote QUOTE-1 (answered; holds the quote 15 minutes)"

echo "Completed processes"
start group-arrival GROUP-1 '{"leader": "Ana", "groupSize": "12", "rooms": [{"number": "301", "type": "double"}, {"number": "302", "type": "suite"}, {"number": "303", "type": "double", "issues": 2}, {"number": "304", "type": "out-of-order"}]}'
start group-arrival GROUP-2 '{"leader": "Luis", "groupSize": "4", "rooms": [{"number": "201", "type": "double"}, {"number": "202", "type": "double"}]}'
start airport-transfer TRANSFER-1 '{"guest": "Erin", "flight": "IB3456"}'
start special-request-agent AGENT-1 '{"guest": "Erin", "room": "204", "specialRequest": "I use a wheelchair"}'
start special-request-agent AGENT-2 '{"guest": "Frank", "room": "118", "specialRequest": "We will arrive late, around midnight"}'
start guest-checkin CHECKIN-2 '{"guest": "Hana", "room": "204"}'
reached CHECKIN-2 wait-room-ready                                         # messages are not buffered: wait for the wait
start room-cleaning CLEAN-204 '{"room": "204"}'                       # its SEND_MESSAGE completes CHECKIN-2
start balance-reminder BALANCE-3 "{\"guest\": \"Ivan\", \"checkIn\": \"$(day 2)\", \"balance\": \"480\"}"
reached BALANCE-3 wait-payment
./message.sh payment-received BALANCE-3 '{"paidAmount": "480"}' > /dev/null && echo "  paid BALANCE-3"
./webhook.sh CM-1001 Alice "$(day 30)" > /dev/null && echo "  webhook CM-1001 (starts booking-onboarding-CM-1001)"
curl -s -X POST "$ENGINE/workflow/api/schedules/nightly-occupancy/run-now" > /dev/null && echo "  ran schedule nightly-occupancy now"
start channel-sync SYNC-1 "{\"room\": \"204\", \"date\": \"$(day 30)\", \"available\": \"3\"}"
start hotel-booking BOOKING-8 '{"guest": "Kim", "rooms": [{"number": "201", "type": "double"}]}'

echo "A compensated booking (a penthouse is always sold out at the hotel)"
start hotel-booking BOOKING-9 '{"guest": "Lee", "rooms": [{"number": "401", "type": "double"}, {"number": "900", "type": "penthouse"}]}'
reached BOOKING-8 approve
reached BOOKING-9 approve
./approve.sh BOOKING-8 true 'Regular guest' > /dev/null && echo "  approved BOOKING-8"
./approve.sh BOOKING-9 true 'VIP' > /dev/null && echo "  approved BOOKING-9"

echo "Incidents: the channel manager is down"
curl -s -X POST "$ENGINE/fake/channel-manager/outage?on=true" > /dev/null
start channel-sync SYNC-2 "{\"room\": \"101\", \"date\": \"$(day 30)\", \"available\": \"0\"}"
start channel-sync SYNC-3 "{\"room\": \"102\", \"date\": \"$(day 30)\", \"available\": \"1\"}"
start channel-sync SYNC-4 "{\"room\": \"103\", \"date\": \"$(day 31)\", \"available\": \"2\"}"
count_is channel-sync ERROR 3                                           # 1 attempt + 2 retries, short backoff

echo
curl -s "$ENGINE/workflow/api/processes?size=100" | python3 -c '
import json, sys, collections
items = json.load(sys.stdin)["content"]
counts = collections.Counter(p["status"] for p in items)
print("Processes by status:", ", ".join(f"{s} {n}" for s, n in sorted(counts.items())))'
echo "The channel manager is still down. End the outage, then retry the incidents from the console:"
echo "  curl -X POST '$ENGINE/fake/channel-manager/outage?on=false'"
