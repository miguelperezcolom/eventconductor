---
title: Configuration Reference
description: Complete reference for all EventConductor configuration properties.
---

## Core properties

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `embedded` | Event dispatch mode |
| `workflow.persistence` | `jpa` \| `memory` | `memory` | Workflow state persistence mode |
| `workflow.projection.enabled` | `true` \| `false` | `false` | Turn on the [process-index read model](/guides/process-index/): emit `ProcessStatusChanged` from `ProcessRepository.save` and run the projector that maintains the `process_index` table. Off = no prior-status read, no event, no projector bean; the write path is unchanged |
| `forms.persistence` | `jpa` \| `memory` | `memory` | Forms state persistence mode. Read only by the forms engine — `workflow.persistence` does not cover it, so an app that embeds both engines has to set both. The standalone forms app overrides the default to `jpa` (`FORMS_PERSISTENCE`) |
| `workflow.timeout-scan-interval-ms` | ms | `10000` | How often the scheduler looks for expired step timeouts and due `TIMER` steps. The lookup is an indexed query on the step's materialised deadline, so its cost tracks the work that is due — normally none — and not how many steps are waiting; lowering it tightens firing latency without a scan penalty |
| `workflow.retry.backoff-base-ms` | ms | `1000` | Auto-retry backoff for the first retry. A failed step with retries left is parked in `AWAITING_RETRY` and re-dispatched only after this delay, so a worker that fails fast is never hammered in a tight loop |
| `workflow.retry.backoff-multiplier` | double | `2.0` | Exponential growth factor applied per attempt: the *n*-th retry waits `base × multiplier^(n-1)`, capped at `backoff-max-ms`. `1.0` = fixed delay |
| `workflow.retry.backoff-max-ms` | ms | `60000` | Upper bound on the backoff delay, however many attempts have failed |
| `workflow.retry.backoff-jitter` | double (0–1) | `0.2` | Randomises each delay by ±this fraction, so a fleet of steps that failed together (a downstream outage) do not all retry on the same tick. `0.0` disables jitter |
| `workflow.cron-scan-interval-ms` | ms | `10000` | How often the scheduler checks `cronExpression` schedules on ACTIVE definitions |
| `workflow.cron-enabled` | `true` \| `false` | `true` | Master switch for cron-scheduled process starts |
| `workflow.outbox-poll-interval-ms` | ms | `500` | How long a relay waits before looking again **when nothing woke it**. A pod raises a signal after committing an outbox row of its own, so this is the fallback for rows written by other pods — a crashed pod's undelivered work, mostly — and no longer the latency every step pays. Before the signal it set per-transition latency directly: 500 ms here meant ~500 ms per transition |
| `workflow.outbox.relay-enabled` | `true` \| `false` | `true` | Enables the outbox relay (set to `false` to disable event relaying entirely) |
| `workflow.process-lock-timeout-seconds` | s | `10` | How long to wait for exclusive access to a process before giving up. Exclusivity is a row lock on the process, so this is a statement timeout on the wait, not a spin: the caller queues in the database and is woken in turn |
| `workflow.outbox.batch-size` | int | `100` | How many messages a relay claims per batch. In `kafka` mode the claim holds a row lock on each until the batch is published, so this also bounds how long one pod can keep another from those rows; raising it trades that for fewer round trips |
| `workflow.outbox.retention` | duration (`24h`, `7d`, `P7D`) | — (off) | How long a **sent** outbox row is kept before it is deleted. Unset means nothing is ever deleted, which is the historical behaviour: every transition of every process leaves its events in that table for good — order of 25 rows per process instance, so millions of processes leave hundreds of millions of rows that autovacuum keeps rewriting and every index scan pays for. `Pending` and `Error` rows are never touched at any setting: one is undelivered work, the other is a parked message waiting for a human |
| `workflow.outbox.purge-batch-size` | int | `1000` | Rows deleted per statement. Bounded so the delete never holds a lock long enough to matter to the relays sharing that database |
| `workflow.outbox.purge-interval` | ISO-8601 duration | `PT1M` | How often a purge pass runs |
| `workflow.outbox.purge-max-batches-per-pass` | int | `20` | Batches one pass may delete. Caps how much a pod that has been down while the table grew can do in one go, so catching up happens over several passes instead of one sweep competing with the engine |
| `workflow.consumer.batch-transaction` | `true` \| `false` | `false` | Try a slice of a poll batch as **one** transaction before falling back to one per process. Each process advancing one step is a commit, so a batch of thirty processes costs thirty fsyncs on the database that gates the whole engine; together they cost one. The fast path never commits part of a slice — it either commits all of it or nothing, and the fallback is the unchanged per-process path starting from the same state — so the failure semantics do not move. It does **not** reduce the commits a process costs over its life; it shares each transition's fsync with the other processes in the same slice, so the saving scales with how many distinct processes a poll batch carries. **Before turning it on:** dispatching a task to a worker is a Kafka send, not a database write, so a rolled-back slice may already have dispatched for the processes it got through and the fallback dispatches them again. That window exists today for a single process; this widens it to a slice. Nothing is lost — the engine already requires workers to be idempotent — but turning this on says that idempotency holds at slice granularity |
| `workflow.consumer.batch-transaction-max-processes` | int | `32` | Processes one transaction may cover. Bounds how long it holds its rows and how much work a single failure throws away |
| `workflow.consumer.batch-transaction-backoff` | int | `20` | Slices to run per-process after the fast path fails before trying it again. Without it a partition carrying a permanently poisoned event would pay for the attempt on every batch and never gain anything |
| `workflow.metrics.gauge-ttl` | duration | `PT30S` | How long the two counting gauges (`eventconductor.process.running`, `eventconductor.outbox.pending`) may reuse their last value. Both answer with a `count(*)` against the engine's own database, and counting running processes walks one index entry per running process — the very number being reported — so without this the cost of observing the system grows with the system, once per pod per scrape. `PT0S` restores a query on every scrape |
| `workflow.message-api.api-key` | string | — | Optional API key required in the `X-Api-Key` header of `POST /workflow/api/messages`. Blank = unauthenticated |
| `workflow.embedded.worker-threads` | int | `0` | Embedded mode only. `0` runs the worker on the thread that dispatched the task — which under `jpa` persistence is the single outbox relay thread, the one advancing **every** process in the JVM, so a worker that blocks stops all of them. Above zero the task is handed to a pool of that many threads and the dispatching thread is freed. See the note below before turning it on |
| `workflow.embedded.worker-queue-capacity` | int | `1000` | How many tasks may wait for a worker thread. A full queue rejects, and the rejection is retryable: the outbox holds the message and offers it again |
| `workflow.embedded.worker-shutdown-grace-ms` | ms | `10000` | How long shutdown waits for in-flight workers before abandoning them |
| `rules.persistence` | `jpa` \| `memory` | `memory` | Rule catalog persistence mode |
| `rules.source` | `local` \| `classpath` \| `rest` \| `grpc` | `local` | Where the rule runtime reads rules from |
| `rules.catalog.url` | URL | — | Catalog base URL for `rules.source=rest` |
| `rules.catalog.grpc-target` | `host:port` | — | Catalog gRPC target for `rules.source=grpc` |
| `rules.cache.ttl` | ISO-8601 duration | `PT5M` | Remote rule cache TTL (`PT0S` = never expires) |
| `rules.kafka-refresh` | `true` \| `false` | `false` | Refresh the rule cache from `RulePublished`/`RuleDeleted` events |
| `rules.grpc.enabled` | `true` \| `false` | `false` | Start the catalog's gRPC read API |
| `rules.grpc.port` | port | `9090` | Catalog gRPC port |

### Where an embedded worker runs

By default the engine calls your `EmbeddedTaskExecutor` on the thread that dispatched the task and
waits for it to return. With `workflow.persistence=jpa` that thread is `embedded-outbox-relay`,
the single thread that drains the outbox and therefore the only one advancing every process in the
JVM. A worker that blocks there — an HTTP call to a service that accepts the connection and never
answers — stops the engine: no step-over runs, and processes created afterwards sit with every
step in `CREATED`, which reads as "waiting for its preconditions" and looks nothing like the
problem it is.

`workflow.embedded.worker-threads > 0` hands the task to a pool instead. Three things change:

- **Delivery stops meaning completion.** Inline, the outbox row is marked `Sent` only once the
  worker returned, so a crash mid-task redelivers it. Through a pool, `Sent` means handed off —
  what it already means in `kafka` mode. A task lost to a crash after the handoff is recovered by
  the step's own `timeout`, so give ACTION steps one (or set `workflow.default-step-timeout-ms`)
  before enabling this.
- **Redelivery stops retrying the worker.** Inline, a retryable failure propagates and the relay
  re-dispatches next cycle. Off-thread the step is failed instead, and the ordinary retry and
  compensation pipeline takes over.
- **Tasks of one process can overlap.** Reporting is still serialised by the process lock, but a
  worker can no longer assume it is called one at a time.

Either way, an exception that escapes a worker now fails its step. Reporting `ERROR` through
`UpdateStepExecutionUseCase` is still the contract — a throw carries no variables and no message
of your choosing — but a step whose worker threw is no longer left waiting for a reply that is
never coming.

## Git import (`workflow.git-import.*`)

Clones Git repositories at startup and imports workflow definition files (`.ec`, `.json`, `.yaml`, `.yml`). `.ec` is EventConductor's first-class extension — its content may be JSON or YAML (detected automatically). Only available when `workflow.persistence=jpa`.

| Property | Default | Description |
|---|---|---|
| `workflow.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `workflow.git-import.repositories[].branch` | `main` | Branch to check out |
| `workflow.git-import.repositories[].directory` | — | Subdirectory to scan for definitions (relative to the repo root). Leave blank to scan the whole repository. |
| `workflow.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `workflow.git-import.repositories[].password` | — | Password or personal access token |
| `workflow.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Leave blank to disable signature verification. |

The webhook endpoint is `POST /workflow/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and archives definitions removed from the repo. See [Workflow Definitions — Importing from Git](/guides/workflow-definitions/#importing-from-git) for setup instructions.

## Git import — Forms engine (`forms.git-import.*`)

Clones Git repositories at startup and imports form definition files (`.json`, `.yaml`, `.yml`). Provided by the `forms-engine` module.

| Property | Default | Description |
|---|---|---|
| `forms.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `forms.git-import.repositories[].branch` | `main` | Branch to check out |
| `forms.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `forms.git-import.repositories[].password` | — | Password or personal access token |
| `forms.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Leave blank to disable signature verification. |

The webhook endpoint is `POST /forms/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and deletes forms removed from the repo. See [Form Definitions — Importing from Git](/guides/form-definitions/#importing-from-git) for setup instructions.

## Git import — Rule engine (`rules.git-import.*`)

Clones Git repositories at startup and imports rule definition files (`.json`, `.yaml`, `.yml`). Provided by the `rule-engine` module.

| Property | Default | Description |
|---|---|---|
| `rules.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `rules.git-import.repositories[].branch` | `main` | Branch to check out |
| `rules.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `rules.git-import.repositories[].password` | — | Password or personal access token |
| `rules.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Leave blank to disable signature verification. |

The webhook endpoint is `POST /rules/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and deletes rules removed from the repo. See [Rule Definitions — Git import](/guides/rule-definitions/#git-import) for setup instructions.

## Application class (embedded modes)

### Workflow engine

When embedding the workflow engine, use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication`. It sets up the correct component scan — including the UI exclusion filter — automatically.

**`workflow.persistence=memory` (default):**

```java
@WorkflowEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**`workflow.persistence=jpa`:**

```java
@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@WorkflowEmbeddedApplication` is provided by the `workflow-engine` module. It combines `@SpringBootConfiguration`, `@EnableAutoConfiguration`, and a `@ComponentScan` scoped to `io.mateu.workflow` with the UI adapter layer excluded.

### Forms engine

When embedding the forms engine, use `@FormsEmbeddedApplication` instead of `@SpringBootApplication`.

**`forms.persistence=memory` (default):**

```java
@FormsEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**`forms.persistence=jpa`:**

```java
@FormsEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@FormsEmbeddedApplication` is provided by the `forms-engine` module and follows the same pattern as `@WorkflowEmbeddedApplication`.

For `workflow.mode=kafka`, use `@SpringBootApplication` as normal — both engines integrate as regular Spring Boot auto-configurations.

## Database (when `workflow.persistence=jpa`)

EventConductor supports **PostgreSQL**, **MariaDB/MySQL**, and **Oracle**. The distributed locking dialect is auto-detected from the JDBC connection at startup — no extra property is needed.

**PostgreSQL** (default in examples):

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

**MariaDB / MySQL:**

```properties
spring.datasource.url=jdbc:mariadb://localhost:3306/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect
spring.jpa.hibernate.ddl-auto=update
```

**Oracle:**

```properties
spring.datasource.url=jdbc:oracle:thin:@localhost:1521:XE
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.database-platform=org.hibernate.dialect.OracleDialect
spring.jpa.hibernate.ddl-auto=update
```

:::caution[Oracle prerequisite]
The Oracle lock dialect uses the `DBMS_LOCK` package, which is not granted by default.
A DBA must execute the following once before starting the application:

```sql
GRANT EXECUTE ON DBMS_LOCK TO <your_schema_user>;
```
:::

**Each engine ships its own migrations and applies them itself** at startup, whenever it has a data source — embedded exactly as in the standalone apps. In production also set `DDL_AUTO=validate`, so the migrations own the schema and Hibernate only checks it.

Do not turn this off. The migrations are the only place the engine's indexes come from: `ddl-auto=update` emits no index DDL at all, so a schema built without them has primary keys and nothing else, and every deadline scan, outbox claim and message correlation becomes a sequential scan. Measured on a cluster where they were missing, PostgreSQL pinned at 750m of CPU and throughput fell to a fraction.

```properties
workflow.schema.enabled=true                       # forms.schema.*, rules.schema.* for the other two
workflow.schema.table=eventconductor_schema_history
```

| Property | Default | What it does |
|---|---|---|
| `workflow.schema.enabled` | `true` | Apply the engine's migrations at startup. Off means something else owns its tables. |
| `workflow.schema.table` | `eventconductor_schema_history` | Where the engine records **its own** migration history. |
| `forms.schema.*` | `eventconductor_schema_history_forms` | Same, for the forms engine. |
| `rules.schema.*` | `eventconductor_schema_history_rules` | Same, for the rules engine. |

Two things follow from the engine owning its schema:

- **It never writes to `flyway_schema_history`.** That table is yours. Your application's migrations are numbered from V1 and so are the engine's; a shared history would collide them and the second one to run would fail validation on a checksum mismatch. `spring.flyway.*` keeps meaning exactly what it meant before — your migrations, your history — and the engine stays out of it.
- **It adopts an existing schema.** The migrations baseline at 0 and every `V1` is written `IF NOT EXISTS`, so turning this on over tables `ddl-auto` already created applies the indexes and nothing else.

Embedding the engine needs `org.flywaydb:flyway-core` on the classpath (plus the driver module, e.g. `flyway-database-postgresql`) — it is an optional dependency, since a run with `workflow.persistence=memory` needs neither. With `persistence=jpa` and no Flyway the engine warns loudly at startup rather than running indexless in silence.

The standalone apps keep their historical history-table names (`flyway_schema_history`, `…_forms`, `…_rules`) via `FLYWAY_TABLE`, so an existing deployment upgrades in place.

### Distributed locking

How the engine keeps two pods off the same process depends on the mode:

- **`workflow.mode=kafka`** — no per-process lock. Events are keyed by process, so a consumer group hands each process's partition to exactly one orchestrator: a process has a single writer by construction. An optimistic-locking `version` on the `process_entity` and `step_execution_entity` aggregates fences the brief window a Kafka rebalance leaves uncovered (the outgoing pod finishing a record the incoming one now owns) and worker replies that arrive unkeyed — a stale write is rejected instead of overwriting the new owner's work.
- **`workflow.mode=embedded` + `workflow.persistence=jpa`** — a **row lock**. The action runs in a transaction that opens with `SELECT … FOR UPDATE` on the process row and releases on commit; waiting is bounded by `workflow.process-lock-timeout-seconds` (default `10`). Embedded pods share no partitioning, so this is what keeps two of them off the same process. No separate connection and no watchdog are involved.
- **`workflow.mode=embedded` + `workflow.persistence=memory`** — an in-JVM lock (single process only).

Separately, the **singleton background jobs** — the timeout/timer scan, cron-scheduled starts, and the embedded outbox relay — take a short-lived **database advisory lock** so only one pod runs each. The lock dialect is auto-detected from the JDBC connection:

| Database | Lock mechanism |
|---|---|
| PostgreSQL | `pg_try_advisory_lock(bigint)` / `pg_advisory_unlock(bigint)` |
| MariaDB / MySQL | `GET_LOCK(name, 0)` / `RELEASE_LOCK(name)` |
| Oracle | `DBMS_LOCK.REQUEST` / `DBMS_LOCK.RELEASE` (via PL/SQL) |

These locks are held only for the length of one scan and are session-scoped, so a crashed pod's locks are released automatically by the database when its JDBC connection closes — no watchdog needed.

## Kafka (when `workflow.mode=kafka`)

```properties
spring.kafka.bootstrap-servers=localhost:9092
```

### Topic bindings

**The engine wires its own.** Destinations, a group per binding and `batch-mode` for both consumers
arrive as defaults, at the lowest precedence — set any of them yourself and yours wins. What is
left to the application is the list of functions it composes, because only it knows whether a
worker or the forms engine is on the classpath too:

```properties
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent

# Auto-create topics (dev/test only)
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

:::caution[Don't drop `batch-mode` if you wire the bindings yourself]
The engine's consumers take a **batch** — `Consumer<Message<List<DomainEvent>>>` — because a poll
batch is committed as one transaction per process. A binding without `batch-mode` delivers one
unconverted record, so the payload arrives as a `byte[]` and the first event dies with
`ClassCastException: class [B cannot be cast to class java.util.List`; retries exhaust and the
outbox is dead-lettered event by event.

And give each consumer **its own group**. A group whose members subscribe to different topics is
assigned per topic by the default range assignor, and with mixed subscriptions it leaves
partitions with no consumer at all — messages nobody reads, processes that never move.
:::

These are what the defaults set, if you need to name them explicitly:

```properties
# Outbox topic (internal domain events)
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.batch-mode=true
spring.cloud.stream.bindings.outbox.destination=outbox

# Upstream topic (integration events from external services)
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.batch-mode=true
spring.cloud.stream.bindings.upstream.destination=upstream

# Downstream topic (task execution requests to workers) — the worker's own binding
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.bindings.downstream.destination=downstream

# Where events the engine can never process are parked
spring.cloud.stream.bindings.deadLetter.destination=dead-letter
```

### Startup resilience (broker down at startup)

The standalone apps set the following so an unreachable broker at startup fails fast and consumers bind as soon as the broker comes up, instead of blocking the context for ~2 minutes:

```properties
# Retry failed bindings every 10 s so consumers bind once the broker becomes available
spring.cloud.stream.bindingRetryInterval=10

# Bound the broker-provisioning admin timeout (Kafka default is 120 s).
# Kafka requires default.api.timeout.ms >= request.timeout.ms.
spring.cloud.stream.kafka.binder.configuration.default.api.timeout.ms=15000
spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=10000
```

## Sharding (advanced, opt-in)

Run the engine as **N shared-nothing shards** to scale writes past a single database, with shards added
and removed **hot**. Entirely opt-in and off by default — a single-cluster deployment is unchanged and
never touches any of this. Design and deployment: `k8s/scale/sharded/README.md` and
`k8s/reliability/ELASTIC-SHARDING-DESIGN.md` in the benchmark module.

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.sharding.enabled` | `true` \| `false` | `false` | Master switch. On: `MessageReceived` routes to a shared `messages` topic every shard consumes (cross-shard `SEND_MESSAGE`→`WAIT_FOR_MESSAGE`), and operator commands (retry/cancel/pause/resume) route to the shard that owns the process. Off: messages and commands go through this shard's own `upstream` exactly as before |
| `workflow.sharding.shard-id` | string | — | This shard's id. Stamped on every `ProcessStatusChanged` (so the read-model row records where the process lives — the lookup command routing and ingress idempotency use). Leave unset when not sharded |
| `workflow.sharding.active-shards` | csv | — | Static list of active shard ids the ingress router places new processes across (round-robin). Overridden by `registry-file` when set |
| `workflow.sharding.registry-file` | path | — | Path to a file listing the active shard ids (comma/newline separated, `#` comments). Re-read on an interval, so editing it — in Kubernetes, a mounted ConfigMap — scales the fleet hot, no restart. Keeps the last good list on a read error. When set, it is the active-shard source instead of `active-shards` |
| `workflow.sharding.registry-refresh-ms` | ms | `5000` | How often `registry-file` is re-read |

Each shard is the stock engine re-pointed by config: its own `DB_URL`, per-shard Kafka bindings
(`upstream-<i>`/`downstream-<i>`/`outbox-<i>`/`dead-letter-<i>` via `spring.cloud.stream.bindings.*.destination`),
and the one shared `messages` topic consumed under a **per-shard consumer group**. See the sharded
manifests for the full set of overrides.

## Complete configurations by mode

### Mode: embedded + memory (no external dependencies)

```properties
workflow.mode=embedded
workflow.persistence=memory
```

Kafka and JPA autoconfiguration are excluded automatically — no `spring.autoconfigure.exclude` needed.

### Mode: embedded + jpa (PostgreSQL, MariaDB, Oracle, or H2)

```properties
workflow.mode=embedded
workflow.persistence=jpa

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

Kafka autoconfiguration is excluded automatically. Workflow definitions placed in `classpath:/workflows/` are imported into the database at startup.

### Mode: kafka + jpa (full distributed, PostgreSQL, MariaDB, or Oracle)

```properties
workflow.mode=kafka
workflow.persistence=jpa

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update

spring.kafka.bootstrap-servers=localhost:9092

# The engine's own bindings (destinations, a group each, batch-mode) come as defaults.
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

## Observability

Metrics (Micrometer/Prometheus) and distributed tracing (OpenTelemetry/OTLP), including the meter
catalogue for every engine and how to enable Prometheus scraping and OTLP export, are documented on
the dedicated [Observability](/reference/observability/) page.

## HTTP security

The standalone apps protect their HTTP endpoints (UI, REST, actuator) with basic authentication, configured via `eventconductor.security.*`:

| Property | Env variable | Default | Description |
|---|---|---|---|
| `eventconductor.security.enabled` | `SECURITY_ENABLED` | `true` | Master switch for HTTP security |
| `eventconductor.security.user` | `SECURITY_USER` | `admin` | Username |
| `eventconductor.security.password` | `SECURITY_PASSWORD` | *(blank)* | Password. If left blank, Spring Boot generates a one-shot password at startup and logs it — set it explicitly in production |

## Docker / environment variables

The standalone images are fully configured via environment variables. All variables have defaults so only values that differ from the defaults need to be set. Note that `apps/docker-compose.yml` overrides several of them (it sets `SERVER_PORT=8080` inside each container and the compose Postgres credentials `workflow`/`secret`).

### orchestrator-standalone-app

| Variable | Default | Maps to |
|---|---|---|
| `SERVER_PORT` | `8105` | `server.port` |
| `WORKFLOW_MODE` | `kafka` | `workflow.mode` |
| `WORKFLOW_PERSISTENCE` | `jpa` | `workflow.persistence` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/workflow` | `spring.datasource.url` |
| `DB_USERNAME` | `user_app` | `spring.datasource.username` |
| `DB_PASSWORD` | `user_password` | `spring.datasource.password` |
| `DB_DRIVER` | `org.postgresql.Driver` | `spring.datasource.driver-class-name` |
| `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | `spring.jpa.database-platform` |
| `DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto` |
| `FLYWAY_ENABLED` | `true` | `workflow.schema.enabled` |
| `FLYWAY_TABLE` | `flyway_schema_history` | `workflow.schema.table` |
| `DB_POOL_SIZE` | `16` | `spring.datasource.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.datasource.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |
| `KAFKA_CONCURRENCY` | `3` | Consumer threads per pod on each consumer binding |
| `KAFKA_MIN_PARTITIONS` | `6` | `spring.cloud.stream.kafka.binder.min-partition-count` |
| `DEFAULT_STEP_TIMEOUT_MS` | `0` (off) | `workflow.default-step-timeout-ms` — fallback deadline for ACTION and RULE steps that declare none |
| `STALLED_STEP_AFTER_MS` | `900000` | `workflow.stalled-step-after-ms` — how long a live step with no deadline may wait before `eventconductor.steps.stalled` counts it |
| `SECURITY_ENABLED` | `true` | `eventconductor.security.enabled` |
| `SECURITY_USER` | `admin` | `eventconductor.security.user` |
| `SECURITY_PASSWORD` | *(blank)* | `eventconductor.security.password` |
| `TRACING_SAMPLING` | `0.0` | `management.tracing.sampling.probability` |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | `management.otlp.tracing.endpoint` |
| `OTLP_METRICS_ENABLED` | `false` | `management.otlp.metrics.export.enabled` — push metrics to a collector as well as exposing them for scraping |
| `OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | `management.otlp.metrics.export.url` |
| `OTLP_METRICS_INTERVAL` | `60s` | `management.otlp.metrics.export.step` |

### forms-standalone-app

| Variable | Default | Maps to |
|---|---|---|
| `SERVER_PORT` | `8106` | `server.port` |
| `WORKFLOW_MODE` | `kafka` | `workflow.mode` |
| `WORKFLOW_PERSISTENCE` | `jpa` | `workflow.persistence` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/workflow` | `spring.datasource.url` |
| `DB_USERNAME` | `user_app` | `spring.datasource.username` |
| `DB_PASSWORD` | `user_password` | `spring.datasource.password` |
| `DB_DRIVER` | `org.postgresql.Driver` | `spring.datasource.driver-class-name` |
| `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | `spring.jpa.database-platform` |
| `DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto` |
| `FLYWAY_ENABLED` | `true` | `forms.schema.enabled` |
| `FLYWAY_TABLE` | `flyway_schema_history_forms` | `forms.schema.table` |
| `DB_POOL_SIZE` | `16` | `spring.datasource.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.datasource.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |
| `SECURITY_ENABLED` | `true` | `eventconductor.security.enabled` |
| `SECURITY_USER` | `admin` | `eventconductor.security.user` |
| `SECURITY_PASSWORD` | *(blank)* | `eventconductor.security.password` |
| `TRACING_SAMPLING` | `0.0` | `management.tracing.sampling.probability` |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | `management.otlp.tracing.endpoint` |
| `OTLP_METRICS_ENABLED` | `false` | `management.otlp.metrics.export.enabled` — push metrics to a collector as well as exposing them for scraping |
| `OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | `management.otlp.metrics.export.url` |
| `OTLP_METRICS_INTERVAL` | `60s` | `management.otlp.metrics.export.step` |

The **rule-standalone-app** follows the same variable set with `SERVER_PORT` defaulting to `8107`, plus `RULES_PERSISTENCE` (`jpa`), `RULES_GRPC_ENABLED` (`true`) and `RULES_GRPC_PORT` (`9090`).

### Switching databases via environment variables

To use MariaDB instead of PostgreSQL, override the three DB-related variables:

```shell
DB_URL=jdbc:mariadb://db:3306/workflow
DB_DRIVER=org.mariadb.jdbc.Driver
JPA_DIALECT=org.hibernate.dialect.MariaDBDialect
```

For Oracle (remember the `DBMS_LOCK` grant prerequisite):

```shell
DB_URL=jdbc:oracle:thin:@db:1521:XE
DB_DRIVER=oracle.jdbc.OracleDriver
JPA_DIALECT=org.hibernate.dialect.OracleDialect
```

## ia-agent-service configuration

See [ia-agent-service](/guides/ia-agent-service/) for the full `application.yaml` reference.

Key environment variables:

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key (required) |
