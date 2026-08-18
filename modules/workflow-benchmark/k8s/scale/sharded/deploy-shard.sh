#!/usr/bin/env bash
# Render and apply one shard (Postgres + orchestrator, re-pointed by config), or drain one.
#
#   ENGINE_IMAGE=miguelperezcolom/orchestrator-standalone-app:<tag> ./deploy-shard.sh add 2
#   ./deploy-shard.sh drain 2      # remove from the registry; the shard keeps running until empty
#   ./deploy-shard.sh delete 2     # tear the stack down (do this only after it has drained)
#
# And once, BEFORE the first shard — the fleet database, the compacted projection topic and the
# standalone projector, which the shards' remote projection mode and placement claim both need:
#
#   PROJECTOR_IMAGE=miguelperezcolom/projector-standalone-app:<tag> ./deploy-shard.sh fleet
#   ./deploy-shard.sh backfill 0,1   # seed the fleet database from shards that already have processes
#
# add/drain edit the shard-registry ConfigMap, which every orchestrator re-reads within one refresh —
# so scaling the fleet is hot: no restart of the existing shards.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KCTX="${KCTX:-cloudfleet-hetzner}"
KUBECTL="kubectl --context ${KCTX}"
NS=ec-shard

action="${1:?usage: deploy-shard.sh <fleet|backfill|add|drain|delete> [shard-id]}"
# `fleet` takes no argument and `backfill` takes a list, so the shard id is only required by the rest.
case "$action" in fleet|backfill) shard="";; *) shard="${2:?shard id required}";; esac

registry_now() { ${KUBECTL} -n "$NS" get configmap shard-registry -o jsonpath='{.data.active}' 2>/dev/null | tr ',\n' '\n' | sed 's/#.*//' | awk 'NF'; }
set_registry() { # args: newline-separated ids
  local ids; ids="$(printf '%s\n' "$@" | awk 'NF' | sort -u)"
  ${KUBECTL} -n "$NS" patch configmap shard-registry --type merge \
    -p "{\"data\":{\"active\":\"$(printf '%s\\n' $ids)\"}}"
}

case "$action" in
  fleet)
    # The shared half: one database (index + placements), the compacted topic, one projector. Applied
    # before any shard, because a shard in remote projection mode reads the fleet database at startup
    # and claims a placement on the creation path.
    : "${PROJECTOR_IMAGE:?set PROJECTOR_IMAGE}"
    ${KUBECTL} apply -f "${HERE}/00-shared.yaml"
    ${KUBECTL} apply -f "${HERE}/50-fleet-db.yaml"
    ${KUBECTL} -n "$NS" rollout status deploy/postgres-fleet --timeout=300s
    # Compaction before anything produces to the topic, so the whole log is compactable.
    ${KUBECTL} delete --ignore-not-found -n "$NS" job/process-index-topic
    ${KUBECTL} apply -f "${HERE}/55-process-index-topic.yaml"
    ${KUBECTL} -n "$NS" wait --for=condition=complete job/process-index-topic --timeout=300s
    sed "s#PROJECTOR_IMAGE#${PROJECTOR_IMAGE}#g" "${HERE}/60-projector.yaml" | ${KUBECTL} apply -f -
    ${KUBECTL} -n "$NS" rollout status deploy/projector --timeout=300s
    echo "fleet database, compacted process-index topic and projector are up"
    ;;
  backfill)
    # Seeds the fleet database from the shards' own write databases. The one step that needs the shard
    # list — run by whoever has it. Idempotent, and safe while the fleet is live.
    : "${PROJECTOR_IMAGE:?set PROJECTOR_IMAGE}"
    shards="${2:?comma-separated shard ids required, e.g. 0,1}"
    ${KUBECTL} delete --ignore-not-found -n "$NS" job/process-index-backfill
    sed -e "s#PROJECTOR_IMAGE#${PROJECTOR_IMAGE}#g" -e "s#SHARDS#${shards}#g" "${HERE}/65-backfill.yaml" \
      | ${KUBECTL} apply -f -
    ${KUBECTL} -n "$NS" wait --for=condition=complete job/process-index-backfill --timeout=900s
    ${KUBECTL} -n "$NS" logs job/process-index-backfill --tail=20
    ;;
  add)
    : "${ENGINE_IMAGE:?set ENGINE_IMAGE}"
    ${KUBECTL} apply -f "${HERE}/00-shared.yaml"
    sed -e "s#ENGINE_IMAGE#${ENGINE_IMAGE}#g" -e "s#SHARD#${shard}#g" "${HERE}/shard.yaml" | ${KUBECTL} apply -f -
    ${KUBECTL} -n "$NS" rollout status deploy/postgres-"$shard" --timeout=300s
    ${KUBECTL} -n "$NS" rollout status deploy/orchestrator-"$shard" --timeout=300s
    # Autoscaling on Kafka lag, if KEDA is installed (harmless to skip otherwise).
    if ${KUBECTL} get crd scaledobjects.keda.sh >/dev/null 2>&1; then
      sed "s#SHARD#${shard}#g" "${HERE}/keda.yaml" | ${KUBECTL} apply -f -
    else
      echo "KEDA not installed (no scaledobjects.keda.sh CRD) — skipping autoscaling for shard $shard"
    fi
    # Only announce it to the registry once it is up, so ingress never routes to a shard that is not ready.
    set_registry $(registry_now) "$shard"
    echo "shard $shard added and now active"
    ;;
  drain)
    # Stop new work; the shard finishes what it holds. Safe to leave draining for as long as it needs.
    set_registry $(registry_now | grep -vx "$shard" || true)
    echo "shard $shard draining (removed from registry); delete it once its processes reach terminal"
    ;;
  delete)
    sed "s#SHARD#${shard}#g" "${HERE}/keda.yaml" | ${KUBECTL} delete --ignore-not-found -f - 2>/dev/null || true
    sed -e "s#SHARD#${shard}#g" "${HERE}/shard.yaml" | ${KUBECTL} delete --ignore-not-found -f -
    echo "shard $shard deleted"
    ;;
  *) echo "unknown action: $action" >&2; exit 2 ;;
esac
