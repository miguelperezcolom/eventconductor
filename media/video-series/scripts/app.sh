#!/usr/bin/env bash
# Starts / stops the app a chapter is recorded from, fresh each time (H2 in memory: a clean slate).
#   scripts/app.sh start <example dir, relative to the repo>   e.g. examples/workshop-hotel-booking
#   scripts/app.sh stop
# Uses JDK 21 and `mvn spring-boot:run`; MAVEN_ARGS (e.g. -Dmaven.repo.local=…) is honoured by Maven.
# The engine comes from the local Maven repository (1.0-SNAPSHOT): install it from the repo root first
# (mvn -Djacoco.skip=true install -DskipTests) so the video shows the console of this checkout.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"; repo="$(cd "$here/../.." && pwd)"
PORT="${PORT:-8095}"; pidf="$here/build/app.pid"; log="$here/build/app.log"
case "$1" in
  start)
    if curl -s -o /dev/null "http://localhost:$PORT/"; then echo "port $PORT is busy — stop what runs there first" >&2; exit 1; fi
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
    mkdir -p "$here/build"
    (cd "$repo/$2" && nohup mvn -B -q spring-boot:run > "$log" 2>&1 & echo $! > "$pidf")
    for i in $(seq 1 120); do
      [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/workflow/api/processes")" = 200 ] && { echo "app up on :$PORT ($2)"; exit 0; }
      sleep 2
    done
    echo "app did not start — see $log" >&2; exit 1 ;;
  stop)
    [ -f "$pidf" ] && pkill -P "$(cat "$pidf")" 2>/dev/null; [ -f "$pidf" ] && kill "$(cat "$pidf")" 2>/dev/null; rm -f "$pidf"
    for i in $(seq 1 30); do lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null || break; sleep 1; done
    pid=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$pid" ] && ps -o command= -p "$pid" | grep -q "$repo"; then kill "$pid"; fi
    echo "app stopped" ;;
esac
