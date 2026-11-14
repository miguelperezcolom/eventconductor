#!/usr/bin/env bash
# Starts / stops the app a chapter is recorded from, fresh each time (H2 in memory: a clean slate).
#   scripts/app.sh start <example dir, relative to the repo>   e.g. examples/workshop-hotel-booking
#   scripts/app.sh stop
# Optional env: PORT (the app's port; set → passed as --server.port), APP_ARGS (more Spring arguments, e.g.
# "--spring.profiles.active=observability"), APP_NAME (a second app of the same chapter keeps its own pid/log).
# Uses JDK 21 and `mvn spring-boot:run`; MAVEN_ARGS (e.g. -Dmaven.repo.local=…) is honoured by Maven.
# The engine comes from the local Maven repository (1.0-SNAPSHOT): install it from the repo root first
# (mvn -Djacoco.skip=true install -DskipTests) so the video shows the console of this checkout.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"; repo="$(cd "$here/../.." && pwd)"
ARGS="${APP_ARGS:-}"; [ -n "${PORT:-}" ] && ARGS="--server.port=$PORT${ARGS:+ $ARGS}"
PORT="${PORT:-8095}"; name="${APP_NAME:-app}"; pidf="$here/build/$name.pid"; log="$here/build/$name.log"
case "$1" in
  start)
    if curl -s -o /dev/null "http://localhost:$PORT/"; then echo "port $PORT is busy — stop what runs there first" >&2; exit 1; fi
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
    mkdir -p "$here/build"
    (cd "$repo/$2" && nohup mvn -B -q spring-boot:run ${ARGS:+"-Dspring-boot.run.arguments=$ARGS"} > "$log" 2>&1 & echo $! > "$pidf")
    for i in $(seq 1 120); do
      # Ready when Spring says so: the API answers before the startup runners have imported the definitions.
      grep -qE 'Started [A-Za-z]+ in [0-9.]+ seconds' "$log" 2>/dev/null \
        && [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/workflow/api/processes")" =~ ^(200|401)$ ]] && { echo "app up on :$PORT ($2)"; exit 0; }   # 401: an app with the secure defaults on
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
