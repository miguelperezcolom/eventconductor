#!/usr/bin/env bash
# Captures every real console state used by the video (see scripts/record-real.mjs):
#   1. build the app, run definition v1 with booking BK-4711, capture the main states;
#   2. change the definition file (v2: retries on "create"), restart on the same H2 file with
#      booking BK-4712, capture the Versions tab — v1 and v2 side by side.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
port="${PROMO_PORT:-8197}"
java="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}/bin/java"
app="$here/build/app"
bash scripts/prepare-app.sh >/dev/null
rm -rf "$app/data" build/real

(cd "$app" && ./mvnw -B -q -DskipTests package >/dev/null)
start_app() {  # $1 business key
  (cd "$app" && "$java" -Dpromo.worker-delay-ms=900 -Dpromo.business-key="$1" \
      -jar target/workflow-embedded-0.0.1-SNAPSHOT.jar --server.port="$port" > "$here/build/app-run.log" 2>&1 &)
  for _ in $(seq 1 120); do grep -q "Started EmbeddedApplication" "$here/build/app-run.log" 2>/dev/null && break; sleep 1; done
  sleep 12   # let the booking run to its end (COMPENSATED)
}
stop_app() { pkill -f "workflow-embedded-0.0.1-SNAPSHOT.jar --server.port=$port" || true; sleep 3; }

stop_app
start_app BK-4711
node scripts/record-real.mjs "http://localhost:$port" main
stop_app
perl -0pi -e 's/(- \{ id: create, type: ACTION, name: Create booking, topic: create-booking, preconditionStepId: start)( \})/$1, retries: 3$2/' \
  "$app/definitions/hotel-booking.yaml"
grep -q "retries: 3" "$app/definitions/hotel-booking.yaml"
start_app BK-4712
node scripts/record-real.mjs "http://localhost:$port" versions
stop_app
echo "real states in build/real"
