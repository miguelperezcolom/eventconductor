#!/usr/bin/env bash
# Starts / stops the app a chapter is recorded from, fresh each time (H2 in memory: a clean slate).
#   scripts/app.sh start <example dir, relative to the repo>   e.g. examples/workshop-hotel-booking
#   scripts/app.sh stop
#   scripts/app.sh port <example dir>      prints the port it runs on
# Port: $PORT, else the app's own server.port (application.properties), else 8095. A PORT other than the
# app's own is passed to it as --server.port (its fakes and connections follow ${server.port}).
# Uses JDK 21 and `mvn spring-boot:run`; MAVEN_ARGS (e.g. -Dmaven.repo.local=…) is honoured by Maven.
# The engine comes from the local Maven repository (1.0-SNAPSHOT): install it from the repo root first
# (mvn -Djacoco.skip=true install -DskipTests) so the video shows the console of this checkout.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"; repo="$(cd "$here/../.." && pwd)"
own_port() { sed -n 's/^server\.port=\([0-9]*\).*/\1/p' "$repo/$1/src/main/resources/application.properties" 2>/dev/null | head -1; }
pidf="$here/build/app.pid"; log="$here/build/app.log"
case "$1" in
  port) p="${PORT:-$(own_port "$2")}"; echo "${p:-8095}" ;;
  start)
    own="$(own_port "$2")"; PORT="${PORT:-${own:-8095}}"
    args=(); [ "$PORT" != "$own" ] && args=("-Dspring-boot.run.arguments=--server.port=$PORT")
    if curl -s -o /dev/null "http://localhost:$PORT/"; then echo "port $PORT is busy — stop what runs there first" >&2; exit 1; fi
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
    mkdir -p "$here/build"
    (cd "$repo/$2" && nohup mvn -B -q spring-boot:run ${args[@]+"${args[@]}"} > "$log" 2>&1 & echo $! > "$pidf")
    for i in $(seq 1 120); do
      # Ready when Spring says so: the API answers before the startup runners have imported the definitions.
      grep -qE 'Started [A-Za-z]+ in [0-9.]+ seconds' "$log" 2>/dev/null \
        && [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/workflow/api/processes")" = 200 ] && { echo "$PORT" > "$here/build/app.port"; echo "app up on :$PORT ($2)"; exit 0; }
      sleep 2
    done
    echo "app did not start — see $log" >&2; exit 1 ;;
  stop)
    PORT="${PORT:-$(cat "$here/build/app.port" 2>/dev/null || echo 8095)}"
    [ -f "$pidf" ] && pkill -P "$(cat "$pidf")" 2>/dev/null; [ -f "$pidf" ] && kill "$(cat "$pidf")" 2>/dev/null; rm -f "$pidf" "$here/build/app.port"
    for i in $(seq 1 30); do lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null || break; sleep 1; done
    pid=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$pid" ] && ps -o command= -p "$pid" | grep -q "$repo"; then kill "$pid"; fi
    echo "app stopped" ;;
esac
