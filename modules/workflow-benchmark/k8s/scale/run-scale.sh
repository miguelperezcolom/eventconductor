#!/usr/bin/env bash
# Autonomous scale-validation run: deploy → load → chaos → drain → verify → collect → PASS/FAIL.
#
# One command, no babysitting, an exit code you can gate on, and an artifact bundle to evaluate
# after. Everything it needs is a flag or an env var; the verdict comes from the reconciler reading
# the database, independent of this script.
#
# Prerequisites: kubectl context reachable; the published engine + benchmark images; an image-pull
# secret `regcred` in ec-scale (created by the runbook); chaos-mesh installed if --chaos.
#
# Usage:
#   ENGINE_IMAGE=miguelperezcolom/orchestrator-standalone-app:1.0-beta.024 \
#   BENCH_IMAGE=miguelperezcolom/workflow-benchmark:1.0-beta.024 \
#   ./run-scale.sh --rate 200 --minutes 120 --chaos
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
KCTX="${KCTX:-cloudfleet-hetzner}"
KUBECTL="kubectl --context ${KCTX}"
NS=ec-scale

ENGINE_IMAGE="${ENGINE_IMAGE:?set ENGINE_IMAGE to the orchestrator image}"
BENCH_IMAGE="${BENCH_IMAGE:?set BENCH_IMAGE to the benchmark image}"
RATE=100
MINUTES=60
CHAOS=0
PREFIX="scale-$(date +%Y%m%d-%H%M%S)"
DRAIN_TIMEOUT=1800   # seconds to wait for the system to drain after load stops

while [ $# -gt 0 ]; do
  case "$1" in
    --rate) RATE="$2"; shift 2 ;;
    --minutes) MINUTES="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --chaos) CHAOS=1; shift ;;
    --drain-timeout) DRAIN_TIMEOUT="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

OUT="${HERE}/artifacts/${PREFIX}"
mkdir -p "$OUT"
echo "run ${PREFIX}: rate=${RATE}/s minutes=${MINUTES} chaos=${CHAOS} → ${OUT}"

# Render a manifest with the run's placeholders substituted, and apply it.
apply() {
  sed -e "s#ENGINE_IMAGE#${ENGINE_IMAGE}#g" \
      -e "s#BENCH_IMAGE#${BENCH_IMAGE}#g" \
      -e "s#SOAK_PREFIX#${PREFIX}#g" \
      -e "s#SOAK_RATE#${RATE}#g" \
      "$1" | ${KUBECTL} apply -f -
}

psql() { ${KUBECTL} -n "$NS" exec deploy/postgres -- psql -U eventconductor -d eventconductor -tAc "$1"; }

echo "== deploy infra =="
apply "${HERE}/00-namespace.yaml"
apply "${HERE}/10-postgres.yaml"
apply "${HERE}/20-kafka.yaml"
${KUBECTL} -n "$NS" rollout status deploy/postgres --timeout=300s
${KUBECTL} -n "$NS" rollout status statefulset/kafka --timeout=300s

echo "== deploy engine + workers =="
apply "${HERE}/30-orchestrator.yaml"
apply "${HERE}/40-worker.yaml"
${KUBECTL} -n "$NS" rollout status deploy/orchestrator --timeout=300s
${KUBECTL} -n "$NS" rollout status deploy/worker --timeout=300s

echo "== start load (the driver installs the definition suite on boot) =="
apply "${HERE}/50-driver.yaml"
${KUBECTL} -n "$NS" rollout status deploy/driver --timeout=180s

if [ "$CHAOS" = 1 ]; then
  echo "== start chaos =="
  ${KUBECTL} apply -f "${HERE}/chaos/scale-chaos.yaml"
fi

echo "== run under load for ${MINUTES} min =="
sleep $(( MINUTES * 60 ))

if [ "$CHAOS" = 1 ]; then
  echo "== stop chaos, let it settle (2 min) =="
  ${KUBECTL} delete -f "${HERE}/chaos/scale-chaos.yaml" --ignore-not-found
  sleep 120
fi

echo "== stop load =="
${KUBECTL} -n "$NS" scale deploy/driver --replicas=0
sleep 30  # let the driver's shutdown hook flush the final acked count

echo "== drain (wait for zero live work, up to ${DRAIN_TIMEOUT}s) =="
deadline=$(( $(date +%s) + DRAIN_TIMEOUT ))
while :; do
  live_p=$(psql "SELECT count(*) FROM process_entity WHERE business_key LIKE '${PREFIX}-%' AND status NOT IN ('COMPLETED','CANCELLED','ERROR','COMPENSATED','COMPENSATION_FAILED')" || echo "?")
  outbox=$(psql "SELECT count(*) FROM outbox_message_entity WHERE status <> 'Sent'" || echo "?")
  echo "  live_processes=${live_p} outbox_unsent=${outbox}"
  if [ "$live_p" = "0" ] && [ "$outbox" = "0" ]; then break; fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "  drain timed out — verifying anyway; R2/R3/R5 will flag whatever is stuck." >&2
    break
  fi
  sleep 20
done

echo "== collect load accounting =="
psql "SELECT * FROM soak_progress WHERE prefix='${PREFIX}'" > "${OUT}/soak_progress.txt" || true
psql "SELECT split_part(business_key,'-',2) def, status, count(*) FROM process_entity WHERE business_key LIKE '${PREFIX}-%' GROUP BY def,status ORDER BY def,status" > "${OUT}/outcomes.txt" || true

echo "== verdict =="
${KUBECTL} -n "$NS" delete job/verify --ignore-not-found
apply "${HERE}/60-verify-job.yaml"
set +e
${KUBECTL} -n "$NS" wait --for=condition=complete job/verify --timeout=600s
verify_ok=$?
set -e
${KUBECTL} -n "$NS" logs job/verify | tee "${OUT}/verdict.txt"

echo "== collect a metrics + logs snapshot =="
${KUBECTL} -n "$NS" get pods -o wide > "${OUT}/pods.txt" || true
${KUBECTL} -n "$NS" logs deploy/orchestrator --tail=500 --prefix > "${OUT}/orchestrator.log" 2>/dev/null || true
# Prometheus/Grafana live in the observability namespace; snapshot the alert-worthy series:
for m in eventconductor_process_started_total eventconductor_process_completed_total \
         eventconductor_process_errored_total eventconductor_steps_stalled \
         eventconductor_outbox_pending eventconductor_compensations_failed_total \
         eventconductor_events_dead_lettered_total eventconductor_process_concurrent_writes_rejected_total; do
  ${KUBECTL} -n observability exec deploy/prometheus-server -- \
    wget -qO- "http://localhost:9090/api/v1/query?query=${m}" >> "${OUT}/metrics.json" 2>/dev/null && echo >> "${OUT}/metrics.json" || true
done

if [ "$verify_ok" = 0 ]; then
  echo "RESULT: PASS — artifacts in ${OUT}"
  exit 0
else
  echo "RESULT: FAIL — see ${OUT}/verdict.txt"
  exit 1
fi
