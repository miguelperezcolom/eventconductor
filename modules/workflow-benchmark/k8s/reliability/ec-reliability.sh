#!/usr/bin/env bash
#
# One entry point for the reliability run, so that "we tested this" is a command someone else can
# type rather than a story about an afternoon.
#
# Everything talks to the cluster through kubectl exec. There is no port-forward anywhere on
# purpose: a port-forward dies when the pod behind it dies, and the whole point of these scenarios
# is to kill things.
#
# Usage:  ./ec-reliability.sh <command> [args]
#   deploy            start the soak driver and the workers
#   status            one line: what the engine has done so far
#   watch [seconds]   status on a loop
#   verify            run the invariants and print the verdict
#   drain [seconds]   stop the load, wait for the engine to finish, then verify
#   resume            start the load again after a drain
#   reset             delete every soak process and counter (destructive)
#   psql '<sql>'      run a query
#   sql <file>        run a file
#
set -euo pipefail

NS="${NS:-ec-rel}"
PG="${PG:-deploy/ec-eventconductor-postgres}"
ORCH="${ORCH:-deploy/ec-eventconductor-orchestrator}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Bounded on purpose. `kubectl exec deploy/...` resolves the deployment to a pod and then blocks
# if that pod is being rescheduled — which is not a remote possibility here, it is a thing the
# scenarios cause: killing the orchestrator tier churned the nodes enough that PostgreSQL itself
# moved, and an unbounded exec sat there for seven minutes while the run's clock kept running.
# A missing reading is a missing reading; it must not be reported as a zero.
EXEC_TIMEOUT="${EXEC_TIMEOUT:-45}"

psql_stdin() {
    kubectl -n "$NS" exec -i --request-timeout="${EXEC_TIMEOUT}s" "$PG" -- \
        sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
}

psql_query() {
    printf '%s\n' "$1" | kubectl -n "$NS" exec -i --request-timeout="${EXEC_TIMEOUT}s" "$PG" -- \
        sh -c 'psql -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
}

cmd_deploy() {
    # One file, so this substitution cannot concatenate two manifests into one and silently drop
    # the second — which is exactly what a `sed` over a directory of YAML does.
    sed -e "s|BENCH_IMAGE|${BENCH_IMAGE:?set BENCH_IMAGE to the tag under test}|g" \
        -e "s|SOAK_RATE|${SOAK_RATE:-100}|g" \
        "$HERE/10-soak.yaml" | kubectl -n "$NS" apply -f -
    kubectl -n "$NS" rollout status deploy/soak-worker --timeout=300s
    kubectl -n "$NS" rollout status deploy/soak-driver --timeout=300s
}

cmd_status() {
    # One row, four numbers, because during a scenario the only question is whether the count is
    # still going up.
    psql_query "
      SELECT
        to_char(now(), 'HH24:MI:SS') || '  acked=' ||
        coalesce((SELECT sum(acked) FROM soak_progress), 0) ||
        '  processes=' || (SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%') ||
        '  completed=' || (SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%' AND status = 'COMPLETED') ||
        '  live=' || (SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%' AND status NOT IN ('COMPLETED','CANCELLED')) ||
        '  outbox_pending=' || (SELECT count(*) FROM outbox_message_entity WHERE status <> 'Sent') ||
        '  pods_ready=' || '$(kubectl -n "$NS" get deploy "${ORCH#deploy/}" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo '?')'
    " 2>/dev/null || echo "$(date +%H:%M:%S)  database unreachable"
}

cmd_watch() {
    local every="${1:-5}"
    while true; do cmd_status; sleep "$every"; done
}

cmd_verify() {
    psql_stdin < "$HERE/invariants.sql"
}

# Stops the load and gives the engine a bounded window to finish what it already accepted.
# The window is the measurement: an engine that recovers is one whose live count reaches zero,
# and how long that takes is the recovery time worth quoting.
cmd_drain() {
    local timeout="${1:-600}"
    kubectl -n "$NS" scale deploy/soak-driver --replicas=0
    echo "load stopped; waiting up to ${timeout}s for the engine to drain"
    local start live
    start=$(date +%s)
    while true; do
        live=$(psql_query "SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%' AND status NOT IN ('COMPLETED','CANCELLED')" 2>/dev/null || echo "?")
        local elapsed=$(( $(date +%s) - start ))
        echo "  t=${elapsed}s live=${live}"
        [ "$live" = "0" ] && { echo "drained in ${elapsed}s"; break; }
        [ "$elapsed" -ge "$timeout" ] && { echo "NOT DRAINED after ${timeout}s — see the verdict below"; break; }
        sleep 10
    done
    echo
    cmd_verify
}

cmd_resume() {
    kubectl -n "$NS" scale deploy/soak-driver --replicas=1
    kubectl -n "$NS" rollout status deploy/soak-driver --timeout=300s
}

cmd_reset() {
    kubectl -n "$NS" scale deploy/soak-driver --replicas=0 >/dev/null
    psql_query "
      DELETE FROM step_execution_entity WHERE process_id IN (SELECT id FROM process_entity WHERE business_key LIKE 'soak-%');
      DELETE FROM process_entity WHERE business_key LIKE 'soak-%';
      DELETE FROM outbox_message_entity;
      DROP TABLE IF EXISTS soak_progress;
      SELECT 'reset done';"
}

case "${1:-}" in
    deploy) cmd_deploy ;;
    status) cmd_status ;;
    watch)  shift; cmd_watch "$@" ;;
    verify) cmd_verify ;;
    drain)  shift; cmd_drain "$@" ;;
    resume) cmd_resume ;;
    reset)  cmd_reset ;;
    psql)   psql_query "$2" ;;
    sql)    psql_stdin < "$2" ;;
    *)      sed -n '3,25p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
