# The standalone projector — one read model across every shard

This completes [`ELASTIC-SHARDING-DESIGN.md`](./ELASTIC-SHARDING-DESIGN.md). That document ends with
the sharded write side working and cluster-validated, and one thing openly unfinished: the read side.
Today `ProcessStatusProjectionHandler` runs **in-process on every shard, writing that shard's own
database**, so each shard holds an index of *its own* processes and nothing holds an index of the
fleet. Everything needed for the fleet-wide version was built on purpose — `ProcessStatusChanged`
carries the whole projected shape so a projector needs no access to the write side, it carries
`shardId` so a fanned-out projector can tell shards apart, and `ProcessIndexRepository` is a port so
the store can move — but the projector itself, and the read database it writes to, do not exist.

Two things depend on it, and both are currently degraded rather than broken:

- **Fleet queries.** `listInFlightProcesses` / `countProcessesByStatus` / `findByBusinessKey` answer
  for one shard. The only working cross-shard aggregation is `Reconciler.verifyAcrossShards`, which
  opens a JDBC connection per shard and sums — benchmark-only, and it needs the shard list.
- **Ingress idempotency.** `IngressRouter` rule 1 ("a key that already exists goes back to its shard")
  reads the **local** index, so it only recognises keys placed on the shard doing the routing. A
  redelivered creation routed from elsewhere is round-robined to a *different* shard, where the
  per-shard creation guard cannot see the original, and the fleet grows two processes for one key.
  The cluster run did not surface it because the benchmark driver does the round-robin itself and
  creates each key exactly once.

The second one is the reason this is worth doing properly rather than as a reporting nicety: it is a
**correctness** gap, not a convenience gap. And — the central decision in this document — it is *not*
fixed by making the read model fleet-wide. An eventually-consistent index cannot answer "does this key
already exist" without a race. It needs its own, synchronous answer. So this design has two halves that
share a database and nothing else:

| | Placement claim | Process index |
|---|---|---|
| Written by | the ingress router, **synchronously** | the projector, **asynchronously** |
| Consistency | strongly consistent | eventually consistent |
| Volume | **one insert per process** | one upsert per status change |
| On the critical path | yes (creation blocks on it) | no |
| Answers | "which shard owns this key" | "what is running / how many / where" |

Keeping them separate is what lets the index be as lagged as it likes without ever costing correctness.

## Part 1 — the projector

### The channel: a dedicated, compacted topic

The projector consumes **one shared topic, `process-index`**, not the per-shard `outbox-<i>` topics.

That is the same argument that made the `messages` topic right: *no shard count appears anywhere in
it.* A projector subscribed to `outbox-0..N` has to be told the shard list and reconfigured on every
scale event — which would put a redeploy back into the elastic path we just removed it from. One
shared topic that every shard produces to and the projector consumes needs no list at all. It is also
much cheaper: `outbox-<i>` carries *every* domain event, and the projector wants one class of them.

**Keyed by `processId`** (`PartitionedEvents.send` already does this) so all events for a process land
on one partition, hence one consumer thread — per-process ordering holds however many projector
instances run, and the existing `occurredAt` guard covers redelivery.

**Compacted** (`cleanup.policy=compact`). This is what makes the read database *rebuildable*: with
compaction the topic retains the last event per `processId` forever at bounded size, so a fresh
consumer group replaying from the earliest offset reconstructs the whole index — no backfill job, no
retention window to be caught out by. A time-retention topic gives you a rebuild that silently loses
every process older than the window, which is exactly the failure you discover the day you need it.

### The relay diverts, it does not duplicate

`OutboxRelay.bindingFor` already diverts one event class to a shared topic when sharded — that is how
`MessageReceived` reaches the `messages` topic. `ProcessStatusChanged` gets the same treatment:

```java
private String bindingFor(DomainEvent event) {
    if (sharedMessages && event instanceof MessageReceived) return "messages";
    if (remoteProjection && event instanceof ProcessStatusChanged) return "process-index";
    return "outbox";
}
```

Diverting rather than duplicating is deliberate. Once the index lives in the read database, a *second*
copy in each shard's own database would be a partial index that looks like a complete one — the most
expensive kind of wrong. One index, one writer.

### Where the shard reads it back

The shard still needs to *read* the index: `KafkaCommandPublisher` resolves `processId → shardId`, and
the UI/MCP query tools read it. So a shard in remote mode binds `ProcessIndexRepository` to a
**read-only JDBC adapter over the read database**, using a second `DataSource`
(`workflow.projection.datasource.*`). Same port, same callers, nothing above it changes — which is the
whole reason it was a port.

The alternative — an HTTP read API on the projector — buys isolation and costs a hop, a service on the
command path, and an availability dependency. The read database is already a shared component here;
adding a service in front of it earns nothing until there are consumers outside the fleet.

### Configuration

`workflow.projection.enabled` stays the master switch and keeps its meaning exactly. One new knob:

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.projection.mode` | `embedded` \| `remote` | `embedded` | `embedded`: today's behaviour — events ride `outbox`, the in-process handler projects into the write database. `remote`: the relay diverts `ProcessStatusChanged` to the shared `process-index` topic, the in-process handler is not created, and `ProcessIndexRepository` reads the read database |
| `workflow.projection.datasource.url` / `.username` / `.password` | | — | The read database, in `remote` mode. Read-only from a shard |
| `workflow.projection.topic` | string | `process-index` | The shared projection topic |

A non-sharded deployment never sets any of it and is bit-for-bit unchanged, as is a sharded deployment
that stays on `embedded` (per-shard indexes, today's behaviour, no read database to run).

### Ordering, idempotency, and the upsert

The projection is already correct under redelivery and out-of-order arrival: `ProcessIndexDBRepository.upsert`
skips a write whose `occurredAt` is older than what is stored. But it does it as read-then-write, which
is only safe because partitioning serialises a process. Replace it, on Postgres, with one atomic
statement that does not rely on that:

```sql
INSERT INTO process_index (process_id, business_key, ..., updated_at, shard_id)
VALUES (?, ?, ..., ?, ?)
ON CONFLICT (process_id) DO UPDATE SET
    business_key = EXCLUDED.business_key, ..., updated_at = EXCLUDED.updated_at,
    shard_id = EXCLUDED.shard_id
WHERE EXCLUDED.updated_at >= process_index.updated_at;
```

Atomic, one round-trip instead of two, and the guard survives any future change to how the topic is
partitioned. Selected by dialect, the way `DbLockDialect` already handles this; the portable
read-then-write path stays for H2 and the embedded tests.

### The module extraction

The projector cannot depend on `workflow-engine` — that would drag the entire engine, its beans, and
the write-side schema into a service whose whole point is not to have them. So the read model moves
into a small module of its own, `modules/process-index`, depending only on `modules/shared` (where
`DomainEvent`, `DomainEventHandler` and `ProcessStatusChanged` already live):

```
modules/process-index/
  ProcessIndexRow            (readmodel)          — moved from workflow-engine
  ProcessIndexRepository     (port)               — moved
  ProcessIndexQueryService                        — moved
  ProcessIndexEntity / …EntityRepository / …DBRepository   — moved
  InMemoryProcessIndexRepository                  — moved
  ProcessIndexProjection.apply(ProcessStatusChanged, …)    — new: the projection as a pure function
  db/migration/processindex/V1__process_index.sql — new: the read database's own schema
```

`ProcessIndexProjection` is the one genuinely new piece: the projection extracted from the handler so
that the in-process `ProcessStatusProjectionHandler` (which stays in the engine, because
`DomainEventHandler` registration is an engine concern) and the standalone projector run *the same
code*. Two implementations of one projection that must agree is how read models drift.

Step 1 is a pure move — no behaviour change, existing tests green, package names preserved so nothing
downstream recompiles differently.

**On the schema.** The engine's `V18__process_index.sql` stays exactly where it is; existing
deployments must not see their migration history change. The read database gets its own Flyway
location with its own `V1`, written `IF NOT EXISTS`. The same DDL in two migration sets is a real
(small) duplication, and the alternative — one shared set — would make the read database run every
write-side migration. Duplicating eleven columns is the cheaper mistake.

### The service

`apps/projector-standalone-app`, following the existing app pattern:

- `spring.cloud.function.definition=consumeProcessIndex`, bound to `process-index`, group
  `process-index-projector`, `batch-mode=true` (a batch is one transaction, as everywhere else).
- JPA + Flyway against the read database, `modules/process-index` on the classpath, nothing else.
- Stateless. Scale it in its group; partitions split; per-process ordering holds. KEDA on
  consumer-group lag, like everything else in the fleet.
- Actuator health. Liveness is the process; readiness is "the binder is consuming".

It is *the only writer* of the read database. That is worth enforcing with a database grant, not just
convention.

### What happens when it is down

Nothing stops. The index goes stale; creations are unaffected (the placement claim is a different,
synchronous path — Part 2); command routing falls back to the local `upstream`, where the owner-only
handler throws on the wrong shard and the command is redelivered or dead-lettered rather than dropped.
Fleet listings go stale by the lag. When it comes back it catches up from its committed offsets.

**The projector is not on the critical path, and this design should be read as keeping it that way.**

## Part 2 — the placement claim

The correctness half. Placement must be decided **exactly once per business key**, synchronously,
before the creation is published — the projection's lag makes an index lookup unable to promise that.

One table in the read database:

```sql
CREATE TABLE process_placement (
    business_key varchar(255) PRIMARY KEY,
    shard_id     varchar(255) NOT NULL,
    claimed_at   timestamp    NOT NULL
);
```

and one port, `ProcessPlacementRepository`:

```java
/** The shard this key is placed on: the candidate if this call won the claim, the incumbent if not. */
String claim(String businessKey, String candidateShardId);
```

implemented as a single statement:

```sql
INSERT INTO process_placement (business_key, shard_id, claimed_at)
VALUES (?, ?, now())
ON CONFLICT (business_key) DO UPDATE SET business_key = process_placement.business_key
RETURNING shard_id;
```

The no-op `DO UPDATE` is what makes `RETURNING` yield the incumbent row on conflict (`DO NOTHING`
returns nothing at all) — so the winner and every loser get the same answer in one round-trip, with no
second query and no race between them.

`IngressRouter.shardFor` then becomes: pick a candidate round-robin from the registry, claim, use
whatever comes back. Rule 1 stops consulting the index entirely — the placement table is authoritative
and the index no longer has a correctness job at all.

### Does this reintroduce the bottleneck sharding removed?

No, and the reason is quantitative, so it should be stated as a number rather than a reassurance.
Sharding exists because a single Postgres cannot absorb the **per-step** write stream — every step
transition, plus its outbox row, plus the relay's status updates: tens of writes per process, each
fsync-bound. The placement claim is **one small insert per process**, on a two-column table, with no
outbox and no follow-up. It is smaller than the write load of a single shard by the average step count
of a workflow, which is the ratio by which one placement database serves many shards.

### Failure mode: fail closed

If the claim cannot be made (read database unreachable), **the creation fails** rather than falling
back to routing locally. This is the deliberate choice, and the reasoning is asymmetric:

- A failed creation is retryable at the source — a Kafka redelivery, an HTTP 503, a cron re-fire.
- A duplicated process is **not repairable**. Two processes for one business key means two sets of
  side effects, on two shards, that no one is watching for.

Fail-open would trade a recoverable outage for an unrecoverable data problem. So: fail closed, and
size/HA the placement database accordingly — it is small, so that is cheap.

Creations with no business key skip the claim entirely (nothing to be idempotent about) and are
round-robined as today. Children (`parentStepExecutionId != null`) never reach this path.

### Retention — the trap

A placement row must **outlive the window in which a duplicate creation can still arrive**. Prune it
too early and a late redelivery is placed fresh on another shard, where the per-shard guard cannot see
the original — the exact duplicate this table exists to prevent, reintroduced by housekeeping.

So: prune a placement row only alongside the process itself, and never before Kafka's own retention on
the creation path. For cron's deterministic per-occurrence keys the row must outlive the cron window
too. Default: **do not prune**. The table is two short strings and a timestamp per process; a fleet
doing 10/s accumulates about 300M rows a year, ~20 GB — prune when that is a real problem, deliberately,
not by a default that quietly breaks idempotency.

## Cutover and rebuild

**Cutover** (a fleet already running with per-shard indexes):

1. Deploy the read database and the projector, topic created compacted.
2. Backfill: a one-shot job per shard reading `process_entity` and upserting with that shard's id, and
   the same for `process_placement` from the existing processes' business keys. This is the only step
   that needs the shard list, and it is run by an operator who has it.
3. Flip shards to `workflow.projection.mode=remote`, rolling. Mixed modes during the roll are safe:
   a shard on `embedded` writes its own index (which nothing will read afterwards) while shards on
   `remote` feed the projector.
4. Point the ingress router at the placement claim. Verify against the backfilled table before
   enabling fail-closed.

**Rebuild** (read database lost): recreate the schema, start the projector with a new consumer group
from the earliest offset of the compacted topic. The index reconstructs itself. `process_placement`
does **not** — it is synchronous state, not a projection, so it is backed up and restored like the
write databases, or rebuilt by the same per-shard backfill job as at cutover.

That asymmetry is worth being loud about: **the index is derived and disposable; the placement table
is not.** They live in the same database for operational convenience and have completely different
durability requirements.

## What has to be built

| # | Piece | Where | Kind |
|---|---|---|---|
| 1 | Extract `modules/process-index`; `ProcessIndexProjection` as a pure function | new module | refactor, no behaviour change |
| 2 | Atomic `ON CONFLICT` upsert by dialect | process-index | small |
| 3 | `workflow.projection.mode`; relay diversion; read-only remote adapter + second DataSource | engine | small, gated |
| 4 | `apps/projector-standalone-app` + its migration | new app | small |
| 5 | `process_placement`, `ProcessPlacementRepository`, `IngressRouter` rule 1 rewritten, fail-closed | engine | **the correctness piece** |
| 6 | Backfill job (per shard → read database) | benchmark / ops | one-shot |
| 7 | k8s: `postgres-index`, `60-projector.yaml`, compacted topic, KEDA on projector lag | k8s | config |
| 8 | Benchmark: fleet verdict from the read database, with `verifyAcrossShards` kept as the independent cross-check | benchmark | small |
| 9 | Docs: `process-index.md` gains the remote mode; configuration reference gains the properties | doc | docs |

Order: **1 → 2 → 3 → 4 → 7** (the read model, end to end, provable in a cluster) **→ 5 → 6 → 8 → 9**.

Two notes on that order. Item 5 is the correctness fix and it is tempting to put first — but it is the
one change that can *stop creations*, and it wants the read database already deployed and observed
under load. Item 8 keeps the fan-out reconciler rather than replacing it: a read model verified by
reading the read model proves nothing, so the benchmark's verdict must keep an independent path to the
shards' own databases.

## Tests worth naming up front

- **Projection equivalence.** The in-process handler and the standalone projector, fed the same event
  sequence, produce the same rows. This is what stops the two paths drifting.
- **Rebuild from compaction.** Project N processes, drop the index, replay from the earliest offset,
  assert the index is identical. Proves the disposability claim.
- **Concurrent claim.** M threads claim the same business key with different candidate shards; all M
  get the same answer, and it is one of the candidates. Proves the single-statement claim.
- **Fail-closed claim.** Read database down → creation fails and is redelivered, and no process is
  created. Proves the asymmetry argument was actually implemented.
- **Cross-shard idempotency, end to end.** The same creation delivered twice, routed from two different
  shards, yields exactly one process. This is the gap in one sentence, and its regression test.
