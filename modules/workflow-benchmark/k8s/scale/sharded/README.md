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

## Autoscaling (KEDA)

The compute tier is already elastic via Kafka consumer-group rebalancing — add a pod and partitions
reassign. KEDA (`keda.yaml`, applied per shard by `deploy-shard.sh add` when the operator is present)
decides *when*, from each shard's own **consumer-group lag** — the direct "falling behind" signal,
which matters because the bottleneck is pipeline concurrency, not CPU (rung 2). It scales:

- **orchestrator-<i>** on `outbox-i` + `upstream-i` lag (the domain-event and creation/command/reply
  flows), 1→6 replicas;
- **worker-<i>** on `downstream-i` lag (once the benchmark-side worker deployment exists).

`maxReplicaCount` is capped at partitions ÷ `KAFKA_CONCURRENCY` (a consumer group cannot use more
threads than partitions); `minReplicaCount` is 1 so a shard keeps draining even at zero lag. Install:
`helm install keda kedacore/keda -n keda --create-namespace`. Without KEDA the shards run at their
manifest replica counts — autoscaling is skipped, nothing breaks.

## What the ENGINE gives you here vs. what the BENCHMARK still needs

Everything above is **config only** — the engine re-points by env. The benchmark image is now
shard-aware too (three `bench.*` knobs, empty = single cluster, unchanged):

- **Workers per shard** — `-Dbench.shard=<i>` makes a worker consume `downstream-i` and reply to
  `upstream-i`.
- **Driver ingress** — `-Dbench.shards=0,1,…` makes the load driver round-robin each creation to an
  active shard's `upstream-<i>` (each business key is created once, so a key never lands on two shards).
- **Fan-out reconciler** — `Reconciler.verifyAcrossShards` runs per shard and combines: R1 conservation
  recomputed globally (Σ acked vs Σ present, so it does not false-fail because acked lives on one shard),
  the rest summed. The verify/install roles expand a `{shard}` placeholder in `bench.jdbc.url` across
  `bench.shards`.

### Run the benchmark sharded

```bash
# install definitions on every shard, drive across shards, verify across shards:
-Dbench.jdbc.url=jdbc:postgresql://postgres-{shard}.ec-shard.svc.cluster.local:5432/eventconductor
-Dbench.shards=0,1          # driver + reconciler + installer
-Dbench.shard=0             # a worker's own shard (worker deployment per shard)
```

Still out of scope for the throughput harness: **workflow definitions installed on every shard** (the
`install` role now loops over `bench.shards`), and **cron needs single cluster-wide evaluation** across
shards (the per-shard advisory lock only guards within one DB) — the harness sets `cron-enabled=false`.

## Status

Authored, not yet cluster-validated (like the 3-broker Kafka manifest was at first). The engine paths —
shared-messages binding, command routing, ingress placement, the hot registry — are covered by unit +
e2e tests, and the reconciler fan-out merge by a unit test; a live two-shard run is the end-to-end
validation, which needs the isolated/dedicated capacity the 1M-run findings called for.
