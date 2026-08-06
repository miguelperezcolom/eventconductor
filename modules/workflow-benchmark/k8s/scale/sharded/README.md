# Sharded topology — elastic, hot add/remove

This deploys the engine as **N independent shards** to scale writes past one Postgres, with shards
added and removed **hot** (no reshard, no restart). It is the k8s side of
[`../../reliability/ELASTIC-SHARDING-DESIGN.md`](../../reliability/ELASTIC-SHARDING-DESIGN.md); read
that for the why. Here is the how.

## What a shard is

The stock orchestrator image and a Postgres, re-pointed by config — no new build:

- **Its own database** (`postgres-<i>`), so each shard's WAL fsync stream is independent. That is the
  only thing that does not scale by adding compute, and the whole reason to shard.
- **Per-shard Kafka topics** — `upstream-i`, `downstream-i`, `outbox-i`, `dead-letter-i` — set with
  `SPRING_CLOUD_STREAM_BINDINGS_*_DESTINATION` env. Everything about a process (its steps, its worker
  traffic, its outbox, its children) stays on its shard by construction.
- **One shared `messages` topic**, consumed by every shard under a **per-shard group**
  (`orchestrator-messages-<i>`) so each shard receives *every* message and correlates it locally — the
  single cross-shard channel (`SEND_MESSAGE` → `WAIT_FOR_MESSAGE`).
- `WORKFLOW_SHARDING_ENABLED=true`, `WORKFLOW_SHARDING_SHARD_ID=<i>`, `WORKFLOW_PROJECTION_ENABLED=true`
  (the read model backs the command router and the ingress idempotency check), and the hot registry
  mounted from a ConfigMap.

Files: `00-shared.yaml` (namespace + registry ConfigMap), `shard.yaml` (one shard, `SHARD`-templated),
`deploy-shard.sh` (render/apply/drain/delete + registry edits).

## Deploy

```bash
export ENGINE_IMAGE=miguelperezcolom/orchestrator-standalone-app:<tag>
# Shared Kafka (one cluster) into ec-shard — reuse ../20-kafka.yaml with the namespace swapped:
sed 's/namespace: ec-scale/namespace: ec-shard/' ../20-kafka.yaml | kubectl --context cloudfleet-hetzner apply -f -
./deploy-shard.sh add 0
./deploy-shard.sh add 1
```

`add` applies the shard, waits for it to be ready, then appends its id to the registry — so ingress
never routes to a shard that is not up yet.

## Scale hot

```bash
./deploy-shard.sh add 2        # new shard starts taking a share of NEW processes; nothing migrates
./deploy-shard.sh drain 1      # remove from the registry → shard 1 stops taking new work, finishes its own
./deploy-shard.sh delete 1     # once it has drained (its processes reached terminal), tear it down
```

The registry ConfigMap is re-read every `workflow.sharding.registry-refresh-ms` (5 s), so add/drain
take effect without restarting any existing shard. Draining is bounded by the longest-running process,
never by a data copy — the transient-process property that makes this elastic without migration.

## What the ENGINE gives you here vs. what the BENCHMARK still needs

Everything above is **config only** — the engine re-points by env. To run the *benchmark suite*
sharded, three benchmark-side pieces are still needed (they are code in `workflow-benchmark`, tracked
as the next increments):

- **Workers per shard.** `BenchmarkApps` hardcodes `downstream`/`upstream` destinations; a worker for
  shard *i* must consume `downstream-i` and reply to `upstream-i`. Needs a `bench.shard` suffix on
  those destinations.
- **Driver ingress.** The load driver must hash-route each `ProcessCreationRequested` to `upstream-<i>`
  for an active shard (the engine's `IngressRouter` does this for in-engine creation; the external
  driver needs the same placement + registry read).
- **Fan-out reconciler.** `Reconciler.verify` must run per shard and sum (Σ acked vs Σ present,
  per-shard exactly-once/no-stuck/outbox unioned, saga histogram summed).

Also: **workflow definitions must be installed on every shard** (each shard creates from its own
definitions), and **cron, if used, needs single cluster-wide evaluation** across shards (the per-shard
advisory lock only guards within one DB) — out of scope for the throughput harness, which sets
`cron-enabled=false`.

## Status

Authored, not yet cluster-validated (like the 3-broker Kafka manifest was at first). The engine paths
it exercises — shared-messages binding, command routing, ingress placement, the hot registry — are
covered by unit + e2e tests; a live two-shard run is the validation once the benchmark-side pieces land.
