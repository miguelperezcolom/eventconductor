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

The benchmark side of a shard is three manifests (`SHARD`/`PREFIX`/`RATE`/`SHARDS`-templated like the
engine's `shard.yaml`), applied after the shards are up and in the registry:

```bash
# one worker per shard — consumes downstream-<shard>, replies to upstream-<shard>:
for i in 0 1; do sed -e "s#BENCH_IMAGE#$BENCH#g" -e "s#SHARD#$i#g" 70-worker-shard.yaml | kubectl apply -f -; done
# the driver — round-robins creations across shards and installs the suite on each (bench.shards):
sed -e "s#BENCH_IMAGE#$BENCH#g" -e 's/PREFIX/soak/;s/RATE/8/;s/SHARDS/0,1/' 80-driver.yaml | kubectl apply -f -
# after the driver is stopped and every shard has drained, the fan-out verdict:
sed -e "s#BENCH_IMAGE#$BENCH#g" -e 's/PREFIX/soak/;s/SHARDS/0,1/' 90-verify.yaml | kubectl apply -f -
```

The knobs those manifests set, and why each matters (each was a real bug the first cluster run surfaced):

- **`bench.shards=0,1`** on the driver makes it install the whole suite on *every* shard's database and
  round-robin creations to each shard's `upstream-<i>`. A shard creates only from its own definitions, so
  one missing there dead-letters every process placed on it (`NoSuchElementException` in
  `CreateProcessUseCase`). The `soak` role now installs on all shards, not just the first.
- **`bench.shard=<i>`** on a worker binds it to one shard's `downstream-<i>`/`upstream-<i>`. Its reply
  destination must be the HIGH-precedence ENV `SPRING_CLOUD_STREAM_BINDINGS_UPSTREAM_DESTINATION=upstream-<i>`
  — StreamBridge's dynamic `send("upstream", …)` in the engine's `WorkerReply` does NOT honour a
  low-precedence property, so without the ENV every reply lands on the plain `upstream` topic that no
  sharded orchestrator consumes, and every step times out.
- **`imagePullPolicy: Always`** on the bench pods — the tag is reused across iterations, so the default
  `IfNotPresent` serves a stale image from the node cache.
- Cron stays single-cluster (the per-shard advisory lock only guards within one DB); the harness leaves
  `cron-enabled` at the engine default and drives every process explicitly, so it is not exercised.

## Status

**Cluster-validated (2026-08-07): a live two-shard run PASSES all reliability invariants.** Driven at
8/s to ~4,082 processes across two shards on cloudfleet-hetzner, then drained and verified:

```
PASS — reliability verdict for prefix 'shsmoke'   shards=2   acked=4082   present=4082
  [ok] R1 conservation (Σacked == Σpresent, across shards)   [ok] R2 all terminal
  [ok] R3a no live step after drain   [ok] R3b no silent-stall   [ok] R4 exactly-once
  [ok] R5a outbox relayed   [ok] R5b no poison   [ok] R7 saga outcomes match injected intent
```

The core data plane is proven end to end: round-robin placement is even across shards, each shard's
outbox→Kafka→worker→reply pipeline is independent, the shared `messages` channel and the fan-out
reconciler (global R1) work, and there is zero loss. Getting there surfaced — and fixed — five real
deployment bugs, all now folded into the manifests above and `shard.yaml`: the Kafka manifest's
hardcoded `.ec-scale.svc` in its advertised listeners / KRaft quorum (needs a global namespace swap,
not just `namespace:`); an orchestrator that raced its own Postgres and ran schema-less (fixed with a
`wait-for-postgres` initContainer in `shard.yaml`); the suite installed on only the first shard; a
stale node-cached image; and the worker reply-destination ENV described above. The engine's sharding
code itself needed no change — every fix was deployment configuration.
