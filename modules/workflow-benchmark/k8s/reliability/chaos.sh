#!/usr/bin/env bash
#
# The scenarios. Each one breaks something while load is running, then waits for the engine to
# come back and says how long that took.
#
# What each scenario prints is a recovery time, not a verdict. The verdict comes afterwards, from
# ec-reliability.sh drain — because "it kept going" and "it lost nothing" are different claims and
# only the second one matters.
#
# Every scenario is written to be safe to run twice, and to leave the cluster in the state it
# found it. A scenario that needs a manual repair afterwards is not reproducible.
#
# Usage:  ./chaos.sh <scenario> [args]
#   pod-kill              delete one orchestrator pod
#   pod-kill-all          delete every orchestrator pod at once
#   node-drain            cordon and drain the node running most of the engine
#   db-stop [seconds]     take PostgreSQL down, then bring it back
#   kafka-stop [seconds]  take the broker down, then bring it back
#   rolling-upgrade       restart the orchestrator the way a deploy would
#   definition-change     replace the workflow definition while processes are mid-flight
#   all                   every scenario in order, with a settle window between them
#
set -euo pipefail

NS="${NS:-ec-rel}"
ORCH="${ORCH:-ec-eventconductor-orchestrator}"
PGD="${PGD:-ec-eventconductor-postgres}"
KAFKA="${KAFKA:-ec-eventconductor-redpanda}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EC="$HERE/ec-reliability.sh"

banner() { printf '\n===== %s  (%s) =====\n' "$1" "$(date +%H:%M:%S)"; }

# Prints the count, or nothing at all when the database cannot be reached. Never a zero: during
# a scenario the database is one of the things that may be down or moving, and a zero would be
# read as "the engine lost everything" by the very check that is supposed to detect that.
completed() {
    local n
    n=$("$EC" psql "SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%' AND status = 'COMPLETED'" 2>/dev/null) || return 0
    case "$n" in
        ''|*[!0-9]*) return 0 ;;
        *) printf '%s' "$n" ;;
    esac
}

# The only honest definition of "recovered" for an engine whose job is to make progress: the
# finished count is going up again. Pods reporting Ready is not the same claim — a pod can be
# Ready and consuming nothing, which is the failure mode that cost this project 30% of its
# throughput unnoticed for months.
await_progress() {
    local label="$1" timeout="${2:-300}"
    local before start now runs=0

    # Two consecutive samples that each add at least MIN_DELTA completions, not one. A single
    # sample rising proves nothing: transactions that were already committing when the pods were
    # killed land afterwards, and the first poll then reports a recovery that has not happened.
    # Every scenario in the first run of this script "recovered in 6s" — which was the length of
    # one poll, and residue.
    local MIN_DELTA=3

    before=$(completed)
    start=$(date +%s)
    while true; do
        sleep 5
        now=$(completed)
        local elapsed=$(( $(date +%s) - start ))
        if [ -z "$now" ]; then
            # No reading. Not evidence of anything — hold the baseline and try again.
            echo "  ${label}: t=${elapsed}s database unreachable"
        elif [ -n "$before" ] && [ $(( now - before )) -ge "$MIN_DELTA" ]; then
            runs=$(( runs + 1 ))
            if [ "$runs" -ge 2 ]; then
                echo "  ${label}: progress sustained after ${elapsed}s (completed -> ${now})"
                RECOVERY_SECONDS="$elapsed"
                return 0
            fi
            echo "  ${label}: t=${elapsed}s completed=${now} (rising)"
            before="$now"
        else
            runs=0
            echo "  ${label}: t=${elapsed}s completed=${now}"
            before="$now"
        fi
        if [ "$elapsed" -ge "$timeout" ]; then
            echo "  ${label}: NO SUSTAINED PROGRESS after ${timeout}s — last reading ${now:-none}"
            RECOVERY_SECONDS="none"
            return 1
        fi
    done
}

scenario_pod_kill() {
    banner "pod-kill: one orchestrator dies mid-flight"
    local victim
    victim=$(kubectl -n "$NS" get pods -l app.kubernetes.io/component=orchestrator \
        -o jsonpath='{.items[0].metadata.name}')
    echo "  killing $victim"
    kubectl -n "$NS" delete pod "$victim" --grace-period=0 --force 2>/dev/null
    await_progress "pod-kill" 300
    kubectl -n "$NS" rollout status "deploy/$ORCH" --timeout=420s >/dev/null
}

scenario_pod_kill_all() {
    banner "pod-kill-all: the whole orchestrator tier dies at once"
    kubectl -n "$NS" delete pods -l app.kubernetes.io/component=orchestrator \
        --grace-period=0 --force 2>/dev/null
    await_progress "pod-kill-all" 420
    kubectl -n "$NS" rollout status "deploy/$ORCH" --timeout=420s >/dev/null
}

scenario_node_drain() {
    banner "node-drain: the node hosting most of the engine is taken away"
    local victim
    victim=$(kubectl -n "$NS" get pods -l app.kubernetes.io/component=orchestrator \
        -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
    echo "  draining $victim"
    kubectl cordon "$victim" >/dev/null
    # Local storage is Redpanda's and PostgreSQL's emptyDir/PVC; deleting them here would be a
    # different scenario, and this one is about losing compute.
    kubectl drain "$victim" --ignore-daemonsets --delete-emptydir-data \
        --pod-selector='app.kubernetes.io/component=orchestrator' --timeout=180s >/dev/null 2>&1 || true
    await_progress "node-drain" 420
    kubectl uncordon "$victim" >/dev/null
    echo "  uncordoned $victim"
    kubectl -n "$NS" rollout status "deploy/$ORCH" --timeout=420s >/dev/null
}

scenario_db_stop() {
    local down="${1:-90}"
    banner "db-stop: PostgreSQL is gone for ${down}s"
    kubectl -n "$NS" scale "deploy/$PGD" --replicas=0 >/dev/null
    echo "  database down; waiting ${down}s"
    sleep "$down"
    kubectl -n "$NS" scale "deploy/$PGD" --replicas=1 >/dev/null
    kubectl -n "$NS" rollout status "deploy/$PGD" --timeout=300s >/dev/null
    echo "  database back"
    await_progress "db-stop" 600
}

scenario_kafka_stop() {
    local down="${1:-90}"
    banner "kafka-stop: the broker is gone for ${down}s"
    kubectl -n "$NS" scale "deploy/$KAFKA" --replicas=0 >/dev/null
    echo "  broker down; waiting ${down}s"
    sleep "$down"
    kubectl -n "$NS" scale "deploy/$KAFKA" --replicas=1 >/dev/null
    kubectl -n "$NS" rollout status "deploy/$KAFKA" --timeout=300s >/dev/null
    echo "  broker back"
    await_progress "kafka-stop" 600
}

scenario_rolling_upgrade() {
    banner "rolling-upgrade: the engine is redeployed under load"
    kubectl -n "$NS" rollout restart "deploy/$ORCH" >/dev/null
    kubectl -n "$NS" rollout status "deploy/$ORCH" --timeout=600s >/dev/null
    await_progress "rolling-upgrade" 300
}

# The interesting one. EventConductor claims a process runs against the definition it started
# with, copied into the row at creation, so changing the definition cannot corrupt work already in
# flight. This replaces the definition with a four-step version while thousands of three-step
# processes are running, and the drain afterwards is what tests the claim: the old ones must still
# finish with three action steps, the new ones with four.
scenario_definition_change() {
    banner "definition-change: the workflow is edited while processes are running"
    kubectl -n "$NS" delete job soak-install-v2 --ignore-not-found >/dev/null
    sed -e "s|BENCH_IMAGE|${BENCH_IMAGE:?set BENCH_IMAGE}|g" "$HERE/20-install-job.yaml" \
        | kubectl -n "$NS" apply -f - >/dev/null
    kubectl -n "$NS" wait --for=condition=complete job/soak-install-v2 --timeout=300s >/dev/null
    kubectl -n "$NS" logs job/soak-install-v2 | tail -2
    await_progress "definition-change" 300
}

case "${1:-}" in
    pod-kill)          scenario_pod_kill ;;
    pod-kill-all)      scenario_pod_kill_all ;;
    node-drain)        scenario_node_drain ;;
    db-stop)           shift; scenario_db_stop "$@" ;;
    kafka-stop)        shift; scenario_kafka_stop "$@" ;;
    rolling-upgrade)   scenario_rolling_upgrade ;;
    definition-change) scenario_definition_change ;;
    all)
        # A scenario that fails must not end the run. The whole point is to find out which ones
        # the engine does not survive, and stopping at the first hides every one after it.
        summary=""
        for s in pod-kill pod-kill-all rolling-upgrade kafka-stop db-stop node-drain definition-change; do
            if "$0" "$s"; then verdict="recovered"; else verdict="DID NOT RECOVER"; fi
            summary="${summary}
  ${s}: ${verdict}"
            echo "  settling for 60s before the next scenario"
            sleep 60
        done
        banner "summary"
        printf '%s\n' "$summary"
        echo
        echo "  Recovery here means the engine resumed making progress. Whether it LOST anything"
        echo "  is a different question, answered by: ./ec-reliability.sh drain"
        ;;
    *) sed -n '3,22p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
