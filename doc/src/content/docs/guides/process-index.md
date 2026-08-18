---
title: Process Index (CQRS Read Model)
description: An opt-in, event-sourced read model for listing and counting processes — fast at scale, works non-sharded, and extends to a sharded fleet.
---

The **process index** is a CQRS read model: a denormalised, query-optimised view of every process, kept
up to date by a projector that reacts to status changes. It answers the operational questions — *what is
running right now?*, *find this process by business key*, *how many are in each state?* — from a single
indexed table, instead of scanning the write-side `process`/`step_execution` tables.

It is **opt-in and additive**. With it off (the default) the engine behaves exactly as before and pays
nothing for it. With it on, the read side is separated from the write side, which is what lets those
queries stay fast as the process count grows — and what makes them survive the move to a **sharded**
write side, where no single database holds every process.

:::note
This is a read model for *listing and lookup*. Rich per-definition metrics (rates, throughput, duration
percentiles, bottlenecks) are a separate, always-on feature — see [Process Analytics](/guides/analytics/).
:::

## When to turn it on

| Situation | Recommendation |
|---|---|
| Small / moderate process volumes, single database | Leave it **off**. `listProcesses` and `findByBusinessKey` over the write tables are fine. |
| Large process volumes, "what's running" / fleet counts on the hot path | Turn it **on**. Queries hit one indexed table, not the write model. |
| Sharded write side (processes split across databases) | Turn it **on**. It is the only way to list/aggregate across shards. |

Enable it with a single property:

```properties
workflow.projection.enabled=true
```

That is the whole switch. It works in the default **non-sharded, single-database** deployment (the read
model lives in the same database as the write model) and is the same switch a sharded deployment uses.

## How it works

```mermaid
flowchart LR
  subgraph Write side (command)
    W[Process state change] -->|ProcessRepository.save| O[(outbox / dispatch)]
  end
  O -->|ProcessStatusChanged| P[Projector]
  P -->|upsert| I[(process_index)]
  subgraph Read side (query)
    I --> Q[ProcessIndexQueryService / MCP tools]
  end
```

- **One event, one emit point.** A process's status is set in many places (creation, the per-step
  recompute, the END-transition completion, cancel, pause/resume, retry/restart, the two saga terminals).
  Rather than emit from each — where one *will* eventually be missed, and a missed transition is a read
  model that silently lies — the event is emitted at the single point every one of them funnels through:
  `ProcessRepository.save`. There it diffs the status about to be written against the one currently
  persisted and, only on a real change, rides a `ProcessStatusChanged` on the same outbox as every other
  domain event. No write path can forget it, because no write path does it.

- **The event carries the whole projected shape.** `ProcessStatusChanged` holds the process id, business
  key, definition id/version, status, completion %, and the created/started/finished timestamps — enough
  for the projector to maintain the index *from the event alone*, with no read-back of the write side.
  That is precisely what lets an out-of-process projector run against a **different** database from the
  one that owns the process.

- **The projector is just an upsert.** In the non-sharded default it runs in-process, sharing the engine's
  domain-event dispatch and writing the index in the same database. The identical projection is what a
  standalone projector runs when it consumes the event topics across sharded databases.

- **Ordering is by emit time, not consume time.** Each event is stamped with an `occurredAt` in causal
  order as the write side runs, and the index keeps the latest by that stamp. This matters because a single
  node can dispatch a freshly-created process's events *out of order* — an in-process creation cascade can
  run the process to completion before the creation's own seed event is dispatched — and a consume-time
  stamp would let the stale seed clobber the final state. `occurredAt` also advances monotonically across
  restarts, as wall-clock time does, where a reset in-memory counter would not.

### Cost when disabled

`ProcessRepository.save` checks one boolean (`workflow.projection.enabled`) and, when off, does no
prior-status read, emits no event, and writes no outbox row. The projector bean is not even created
(`@ConditionalOnProperty`). The `process_index` table is created empty by the migration but never touched.
The throughput-critical write path is unchanged.

## Querying it

### Java API

`ProcessIndexQueryService` is the read side:

```java
@Autowired ProcessIndexQueryService processIndex;

// Everything still in flight (PENDING / RUNNING / PAUSED)
List<ProcessIndexRow> live = processIndex.findInFlight();

// ...scoped to one definition
List<ProcessIndexRow> liveForDef = processIndex.findInFlightByDefinition("order-fulfilment");

// Point lookup by the stable business key
Optional<ProcessIndexRow> row = processIndex.findByBusinessKey("order-4711");

// Fleet counts, one row per status
Map<String, Long> counts = processIndex.countByStatus();
```

With the projection off these return empty (nothing populates the index) — use the write-side
`listProcesses` / `findByBusinessKey` on deployments that keep it off.

### MCP tools

Two tools expose the read model to AI assistants (both return empty unless the projection is enabled):

- **`listInFlightProcesses`** — the processes currently PENDING/RUNNING/PAUSED, optionally scoped to one
  workflow definition; a single indexed read that stays fast at scale.
- **`countProcessesByStatus`** — the at-a-glance fleet counts.

The existing `listProcesses` / `findProcessByBusinessKey` tools continue to read the write side, so they
work whether or not the read model is on.

## Storage

The read model is one table, created by migration `V18__process_index.sql` (and by Hibernate DDL where
Flyway is off):

```sql
CREATE TABLE process_index (
    process_id                  varchar(255) PRIMARY KEY,
    business_key                varchar(255),
    workflow_definition_id      varchar(255),
    workflow_definition_version integer NOT NULL DEFAULT 0,
    status                      varchar(255),
    completion_percentage       integer NOT NULL DEFAULT 0,
    created                     timestamp,
    started                     timestamp,
    finished                    timestamp,
    updated_at                  timestamp,   -- the occurredAt ordering key
    shard_id                    varchar(255) -- null when non-sharded
);
-- indexes: (status), (workflow_definition_id, status), (business_key)
```

It is a **hexagonal port** (`ProcessIndexRepository`) with two adapters selected by `workflow.persistence`:
an in-heap map for `memory` and a JPA/SQL table for `jpa`. The same port is where a dedicated read database
would plug in for a sharded deployment.

## Configuration

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.projection.enabled` | `true` \| `false` | `false` | Turn the read model on: emit `ProcessStatusChanged` and run the projector. |
| `workflow.sharding.shard-id` | string | *(empty)* | Recorded on each row as provenance in a sharded deployment; leave unset when non-sharded. See [Sharding configuration](/reference/configuration/#sharding-advanced-opt-in). |

## Running a standalone projector

Everything above describes the **embedded** mode: the projector runs in-process and writes the index into
the engine's own database. That is right for a single cluster, and it is exactly wrong for a sharded one —
each shard would index only its own processes, so "what is running" would have as many partial answers as
there are shards, and none for the fleet.

Set `workflow.projection.mode=remote` and three things change:

- the outbox relay **diverts** `ProcessStatusChanged` to a shared, fleet-wide `process-index` topic
  instead of the shard's own `outbox`;
- the in-process projector is not created (it would never see one anyway — and if it did, it would write
  a second, partial index that looks like a complete one);
- the engine **reads** the index from a read database instead of its own, so `findByBusinessKey`,
  `listInFlightProcesses`, `countProcessesByStatus` and the command router's `processId → shardId` lookup
  all answer for the whole fleet.

```properties
workflow.projection.enabled=true
workflow.projection.mode=remote
workflow.projection.datasource.url=jdbc:postgresql://postgres-fleet:5432/eventconductor_fleet
workflow.projection.datasource.username=eventconductor
workflow.projection.datasource.password=...
```

The projector itself is a small service of its own — `apps/projector-standalone-app`. It depends on the
read model and the event, and deliberately **not** on the engine: `ProcessStatusChanged` carries the whole
projected shape precisely so a projector needs no entities, no write schema and no engine beans. What that
buys is a service small enough to scale, restart and rebuild on its own.

:::note[The topic must be compacted]
`process-index` is keyed by `processId` and should be created with `cleanup.policy=compact`. Under
compaction it retains the last event per process forever at bounded size, so a projector replaying from
the earliest offset reconstructs the entire index — which is what makes the read database *disposable*.
Under the default time retention the same replay silently loses every process older than the window: a
rebuild that appears to succeed and returns an index missing exactly the oldest work.
:::

### It is not on the critical path

If the projector is down, nothing stops. The index goes stale; creations are unaffected (see placement,
below); a targeted command falls back to the local `upstream`, where the owner-only handler throws on the
wrong shard so the command is redelivered or dead-lettered rather than dropped. When the projector comes
back it catches up from its committed offsets.

## Placement: the synchronous half

The read model does **not** decide where a new process goes, and the reason is worth being explicit about,
because the obvious design is wrong.

Placement has to be idempotent: a business key must be placed on exactly one shard, and every redelivery of
that creation must return to it, or the per-shard creation guard cannot collapse the duplicate and the
fleet runs two processes — two sets of side effects, on two shards, that nobody is watching for. An
eventually-consistent index cannot promise that. A creation redelivered before the projection catches up
finds nothing, gets round-robined again, and lands somewhere else.

So placement is claimed synchronously, in one atomic statement, in a table of its own:

```properties
workflow.sharding.placement.datasource.url=jdbc:postgresql://postgres-fleet:5432/eventconductor_fleet
workflow.sharding.placement.datasource.username=eventconductor
workflow.sharding.placement.datasource.password=...
```

Usually the same database as the read model — they are deployed together — but a separate connection pool,
because the index is opened read-only and the placement store must be writable.

|  | Placement claim | Process index |
|---|---|---|
| Written by | the ingress router, **synchronously** | the projector, **asynchronously** |
| Consistency | strongly consistent | eventually consistent |
| Volume | **one insert per process** | one upsert per status change |
| On the critical path | yes — a creation blocks on it | no |
| If lost | restore from backup, or re-run the backfill | replay the compacted topic |

**It does not reintroduce the bottleneck sharding removed.** Sharding exists because a single database
cannot absorb the *per-step* write stream — every transition plus its outbox row, tens of fsync-bound
writes per process. A claim is one small insert per *process*. The ratio between them is the average step
count of a workflow, and that is the ratio by which one placement database serves many shards.

**It fails closed.** If the claim cannot be made, the creation fails rather than being routed anyway. The
reasoning is asymmetric: a failed creation is retryable at its source (a Kafka redelivery, a 503, a cron
re-fire), while a duplicated process is not repairable. Fail-open would trade a recoverable outage for an
unrecoverable data problem.

**Do not prune it casually.** A placement row must outlive the window in which a duplicate creation can
still arrive. Pruned early, a late redelivery is placed fresh on another shard — exactly the duplicate the
table exists to prevent, reintroduced by housekeeping. The default is not to prune.

## Cutover and rebuild

Adopting the fleet-wide read model on a running sharded deployment:

1. Deploy the read database and the projector; create `process-index` compacted.
2. **Backfill** — the projector image doubles as the cutover job:
   ```bash
   java -jar app.jar --backfill.shards=0,1      --backfill.jdbc.url='jdbc:postgresql://postgres-{shard}:5432/eventconductor'
   ```
   It seeds both tables from each shard's write database: the index (so the fleet view is complete from
   the first query) and the placements (so the claim knows where existing keys already live). Idempotent,
   and safe on a live fleet — a backfilled row is stamped with the process's own `created`, so any real
   transition from the topic outranks it. This is the **only** step in the design that needs the shard
   list, and it is run by an operator who has it.
3. Roll the shards to `workflow.projection.mode=remote`. Mixed modes during the roll are safe.
4. Point the ingress router at the placement claim.

To rebuild after losing the read database: recreate the schema and start the projector with a new consumer
group from the earliest offset. The index reconstructs itself. `process_placement` does **not** — it is
synchronous state, not a projection — so it is restored from backup or re-seeded by the same backfill job.
