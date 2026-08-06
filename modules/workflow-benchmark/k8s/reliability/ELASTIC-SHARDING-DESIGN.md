# Elastic sharding — add and remove nodes hot

This supersedes the static `hash(businessKey) % N` sketch in SCALE-VALIDATION-DESIGN.md §4a. That
scheme is **not elastic**: `hash(bk) % N` changes for almost every key when `N` changes, so growing or
shrinking the fleet would re-route in-flight processes to a different shard and break the "everything
about a process stays on its shard" invariant that makes sharding correct. Elastic scaling — add and
remove nodes *hot*, under load, with no stop-the-world reshard — needs a different design.

## Two tiers, two very different elasticity stories

**Compute (orchestrators + workers) is already elastic — for free.** A process is keyed to a Kafka
partition by its business key; orchestrators form a consumer group; Kafka's group rebalance assigns
partitions to consumers. Add an orchestrator pod → Kafka hands it some partitions; kill one → its
partitions move to the survivors. No data moves — the DB is shared — so this is pure, instant,
hot elasticity. It is already how the engine runs. **Autoscale the compute tier on consumer lag / CPU
(HPA or KEDA) and it grows and shrinks with load today, no sharding involved.**

So the *only* thing that does not scale by adding compute is the **single Postgres write path** (the WAL
fsync stream — the ceiling rung 2 measured). Sharding exists solely to scale *that*. The elasticity
problem is therefore narrowly: **how do you add and remove Postgres shards hot?**

## The trap, and the way out: transient processes

Elastic *stateful* sharding is normally hard because moving a shard means migrating its data. The usual
answers are consistent hashing (still moves keys) or a distributed SQL backend (Citus/Yugabyte —
delegate elasticity to the DB, at a consensus-latency cost that hurts the very fsync-latency-bound
throughput we are trying to raise).

EventConductor has a property that makes it much easier: **a process is transient.** It is created,
runs for seconds to minutes, reaches a terminal status, and is done. That turns "rebalance by migrating
data" into "rebalance by *draining*" — and draining needs no migration.

### Drain-based elastic sharding

**Assignment is at creation, stable for the process's life, and recorded — never recomputed.**
- A new process is routed to one of the currently **active** shards by the ingress router (round-robin,
  or least-loaded by the shard's live-process count). The chosen `shardId` is stamped on the process
  and published to that shard's `upstream-<shardId>` topic. From then on the process lives entirely on
  that shard by construction (its steps, outbox, logs, children — §4a's locality argument is unchanged).
- Crucially the shard is **not** a function of the business key, so adding or removing shards never
  re-routes an existing process. The routing map is data, not arithmetic.

**Scale up = add a shard, hot.** Stand up a new `{Postgres + orchestrators + workers}` stack (a shard is
still the stock engine re-pointed by config), register it as `active`. It immediately starts taking a
share of *new* processes. Nothing migrates; existing processes stay where they are.

**Scale down = drain a shard, hot.** Mark a shard `draining` in the registry → the ingress router stops
sending it new processes → its live-process count falls as its in-flight work completes → once zero
(and its outbox is empty), tear the stack down. Bounded by the longest-running process, not by a data
copy. No migration, ever.

This is elastic in both directions, under load, with no reshard and no data movement — at the cost of
one property: **you rebalance by placing new load, not by moving existing load.** A newly added shard
fills up as fresh processes arrive rather than by stealing running ones. For a workload of transient
processes that is exactly right; if instantaneous rebalancing of *running* processes were ever needed,
that is the migration-based variant and a separate, heavier project.

## Routing — the four cases

1. **Ingress (new process): the router picks an active shard.** It reads the shard registry, applies
   round-robin / least-loaded, stamps `shardId`, publishes to `upstream-<shardId>`. This is the only
   place a shard is *chosen*.
2. **In-flight (worker replies, child processes): local by construction.** Workers of shard *i* consume
   `downstream-i` and reply to `upstream-i`; a `PROCESS` step spawns its child on shard *i*. These never
   leave the shard — unchanged from §4a — so hot add/remove of *other* shards cannot disturb them.
3. **Messages (`SEND_MESSAGE` → `WAIT_FOR_MESSAGE`): one shared `messages` topic every shard consumes.**
   The sender does not know the waiter's shard, and with elastic shards it *cannot* be computed. So the
   `MessageReceived` goes to a single shared topic; every shard consumes it and correlates against *its
   own* waiting steps; the shard that owns the waiter wakes it, the rest match nothing and drop it (the
   existing fail-closed contract). This is the **one engine change** sharding needs, and it is exactly
   what makes routing elastic — no shard count appears anywhere in it.
4. **Targeted external commands (retry / cancel / pause a process by id, from UI or MCP): look up the
   shard.** These must reach the owning shard, and the shard is not derivable from the id. This is what
   the **`shard_id` column already built into the CQRS process-index read model** is for: the UI/MCP
   resolves `process-id → shard_id` from the index and routes the command to `upstream-<shardId>` (or,
   as a fallback, broadcasts to all shards and lets the non-owners ignore it — the same fail-closed
   drop as messages). The read model I added for querying at scale doubles as the command router.

## The shard registry

A small piece of dynamic state the ingress router reads: the set of shards currently `active`
(accepting new processes). It must be updatable hot — that is the whole point — and observed quickly.

**Implemented as a watched file** (`ShardRegistry` port; `FileShardRegistry`, `@Primary` over the
static `ConfigShardRegistry`). It reads the active-shard list from `workflow.sharding.registry-file`
and re-reads it every `workflow.sharding.registry-refresh-ms` (default 5 s). In Kubernetes that file is
a ConfigMap mounted as a volume: editing the ConfigMap propagates to the file and takes effect within
one refresh, no restart, no Kubernetes-API dependency in the engine — and it works the same off
Kubernetes. Draining a shard is removing its id from the list; adding one is appending it. File format
is shard ids by comma and/or newline, `#` comments ignored.

**Fail-safe:** a transient read failure (a ConfigMap volume mid-swap, a permissions blip) keeps the
last known good list rather than reporting zero shards — draining the whole fleet because a file was
briefly unreadable would be the worst failure mode. The registry only ever moves to a list it read.

A compacted Kafka topic or a coordination-DB row would also serve (near-instant vs the file's
refresh-interval latency); the file is the cheapest and needs no new infrastructure. Only the ingress
router reads it today; the command router resolves an existing process's shard from the read-model
`shard_id` instead, so it needs no registry.

## What actually has to be built

Almost all of it is config + ops; the engine surface is deliberately tiny.

| Piece | Where | Kind |
|---|---|---|
| Shared `messages` binding: publish `MessageReceived` to a shared topic; a `consumeMessages` consumer on every shard that correlates locally | engine | **code** (small, additive, gated by a `workflow.sharding.enabled`-style flag so non-sharded is unchanged) |
| Stamp `shardId` on a process at creation + carry it into the CQRS index | engine (index already has the column) | **code** (small) |
| Ingress router: pick active shard, publish to `upstream-<shardId>` | driver / an ingress service | code (outside the engine hot path) |
| Command router: `process-id → shard_id` via the index, route retry/cancel/pause | UI/MCP layer | code (small) |
| Shard registry (active shards, hot) — **done**: `FileShardRegistry` watches a file (a mounted ConfigMap), refreshes every few seconds, keeps last-good on read error | engine | **code** (done) |
| Per-shard deployment (DB_URL, `upstream-i`/`downstream-i`/`outbox-i`/`dead-letter-i` bindings) | k8s | **config only** |
| Fanout reconciler (Σ across shards) | benchmark | code (benchmark only) |

The load path — the thing that has to stay fast — is untouched: each shard is the same engine writing
the same way to its own Postgres. Elasticity lives entirely in *routing* (ingress + registry +
messages + command lookup), never in the per-step write path.

## Relationship to what already exists

- The **CQRS process-index** (with its `shard_id` column) is the linchpin that makes elastic routing
  work: it is both the "list/aggregate across shards" answer and the "which shard owns this process"
  command router. It was built shard-ready on purpose.
- The **shared `messages` topic** is the single engine change, and it is what removes every dependence
  on a fixed shard count from the routing — the property that makes the whole thing elastic rather than
  a static `% N`.

## Decisions taken (2026-08-06)

- **Data-tier elasticity: drain-based** (assign-at-creation, add = take new load, remove = drain then
  delete, no migration). Rebalancing places new load rather than moving running processes — correct for
  transient workflow processes.
- **Compute-tier autoscaling: KEDA on Kafka consumer-group lag** — lag is the direct "falling behind"
  signal for the pipeline, per shard.

## Implementation plan (increments)

Each is gated/additive so `main` behaviour is unchanged until a deployment opts in.

1. **Engine — shared `messages` binding (the one core change).** A `MessagePublisher` port + Kafka
   adapter (`StreamBridge.send("messages", …)`) and embedded adapter; a `consumeMessages` consumer that
   correlates locally via the existing `CorrelateMessageUseCase` (same per-process transaction wrapper as
   `consumeUpstream`). Route `MessageReceived` to it from **both** send routes when enabled: external
   sends (MCP `sendMessage`, REST controller) publish via `MessagePublisher` instead of the upstream
   publisher; and the **outbox relay diverts** `MessageReceived` events to the `messages` binding instead
   of `outbox`. Gate with `workflow.sharding.enabled` (default false → unchanged). The consumer's
   group must be **unique per shard** so every shard receives every message (each shard is its own
   deployment, so distinct group ids fall out naturally). Ships with a test proving a message sent from
   "shard A" wakes a waiter on "shard B" and is dropped everywhere else. **This is the reliability-
   critical piece — build and verify it in isolation first.**
2. **Engine — stamp `shardId` at creation.** Read `workflow.sharding.shard-id`, put it on the process at
   creation, and carry it into the CQRS index (the `shard_id` column already exists). No-op when unset.
3. **Command routing.** UI/MCP retry/cancel/pause resolve `process-id → shard_id` from the process-index
   and route to `upstream-<shardId>`; fallback = broadcast to all shards (non-owners drop it, fail-closed).
4. **Ingress router.** Picks an active shard (round-robin / least-loaded from the registry), stamps
   `shardId`, publishes `ProcessCreationRequested` to `upstream-<shardId>`. Lives in the benchmark driver
   for the harness and as a small reusable ingress for real deployments — outside the engine hot path.
5. **Shard registry.** `active | draining` set + connection info; a watched ConfigMap (cheapest) or a
   compacted `shard-registry` topic. Low-write, cache-and-refresh-on-change.
6. **k8s — per-shard deployment templating.** Each shard = the stock engine with `DB_URL` and
   `upstream-i/downstream-i/outbox-i/dead-letter-i` bindings; one shared `messages` topic; the driver
   hash-routes at the door. Config only.
7. **KEDA autoscaling.** A `ScaledObject` per shard scaling its orchestrators/workers on that shard's
   Kafka consumer-group lag. Add/remove shards is a registry edit + a stack up/down; within a shard,
   KEDA grows/shrinks compute hot.
8. **Benchmark — fanout reconciler.** `Reconciler.verify` runs per-shard and sums (Σ acked vs Σ present,
   per-shard exactly-once/no-stuck/outbox unioned, saga histogram summed); `soak_progress` gains a shard
   column. Benchmark-only.

Order: **1 → 2 → (3,4,5 in parallel) → 6 → 7 → 8.** Increment 1 is the only change to the reliability-
critical write/correlate path; everything after it is routing, deployment, and benchmark.
