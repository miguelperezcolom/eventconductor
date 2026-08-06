#!/usr/bin/env bash
# Render and apply one shard (Postgres + orchestrator, re-pointed by config), or drain one.
#
#   ENGINE_IMAGE=miguelperezcolom/orchestrator-standalone-app:<tag> ./deploy-shard.sh add 2
#   ./deploy-shard.sh drain 2      # remove from the registry; the shard keeps running until empty
#   ./deploy-shard.sh delete 2     # tear the stack down (do this only after it has drained)
#
# add/drain edit the shard-registry ConfigMap, which every orchestrator re-reads within one refresh —
# so scaling the fleet is hot: no restart of the existing shards.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KCTX="${KCTX:-cloudfleet-hetzner}"
KUBECTL="kubectl --context ${KCTX}"
NS=ec-shard

action="${1:?usage: deploy-shard.sh <add|drain|delete> <shard-id>}"
shard="${2:?shard id required}"

registry_now() { ${KUBECTL} -n "$NS" get configmap shard-registry -o jsonpath='{.data.active}' 2>/dev/null | tr ',\n' '\n' | sed 's/#.*//' | awk 'NF'; }
set_registry() { # args: newline-separated ids
  local ids; ids="$(printf '%s\n' "$@" | awk 'NF' | sort -u)"
  ${KUBECTL} -n "$NS" patch configmap shard-registry --type merge \
    -p "{\"data\":{\"active\":\"$(printf '%s\\n' $ids)\"}}"
}

case "$action" in
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
