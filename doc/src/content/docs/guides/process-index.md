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

## Relationship to sharding

The read model is the query answer to a sharded **write** side. Sharding splits processes across N
shared-nothing write stacks so writes scale horizontally; once it does, no single database can answer
"what is running across the fleet". Because `ProcessStatusChanged` carries the full projected shape and is
delivered over the shared event bus, a single projector (or a read database fed by one) can maintain a
**fleet-wide** index across all shards — the same projection you already run in-process when non-sharded.
See the scale-validation design for the sharding and CQRS blueprints.
