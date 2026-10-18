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
| `workflow.projection.mode` | `embedded` \| `remote` | `embedded` | Where the read model is maintained. `embedded`: in-process, in the engine's own database — unchanged. `remote`: the outbox relay diverts `ProcessStatusChanged` to the shared `process-index` topic, the in-process projector is not created, and a [standalone projector](/guides/process-index/#running-a-standalone-projector) maintains one fleet-wide index in a read database |
| `workflow.projection.datasource.url` / `.username` / `.password` / `.pool-size` | | — / — / — / `4` | The read database, in `remote` mode. Opened **read-only**: there the projector is the index's only writer |
| `forms.persistence` | `jpa` \| `memory` | `memory` | Forms state persistence mode. Read only by the forms engine — `workflow.persistence` does not cover it, so an app that embeds both engines has to set both. The standalone forms app overrides the default to `jpa` (`FORMS_PERSISTENCE`) |
| `workflow.time.zone` | IANA zone | JVM zone | The zone in which a date without one is read — an `untilVariable` holding `2026-08-01`, a moment (`until` / `deadline`) that names no `zone` |
| `workflow.timeout-scan-interval-ms` | ms | `10000` | How often the scheduler looks for expired step timeouts and due `TIMER` steps. The lookup is an indexed query on the step's materialised deadline, so its cost tracks the work that is due — normally none — and not how many steps are waiting; lowering it tightens firing latency without a scan penalty |
| `workflow.retry.backoff-base-ms` | ms | `1000` | Auto-retry backoff for the first retry. A failed step with retries left is parked in `AWAITING_RETRY` and re-dispatched only after this delay, so a worker that fails fast is never hammered in a tight loop |
| `workflow.retry.backoff-multiplier` | double | `2.0` | Exponential growth factor applied per attempt: the *n*-th retry waits `base × multiplier^(n-1)`, capped at `backoff-max-ms`. `1.0` = fixed delay |
| `workflow.retry.backoff-max-ms` | ms | `60000` | Upper bound on the backoff delay, however many attempts have failed |
| `workflow.retry.backoff-jitter` | double (0–1) | `0.2` | Randomises each delay by ±this fraction, so a fleet of steps that failed together (a downstream outage) do not all retry on the same tick. `0.0` disables jitter |
| `workflow.cron-scan-interval-ms` | ms | `10000` | How often the scheduler checks the schedules (`cronExpression`, `schedules`, `cron.yaml`) of ACTIVE definitions |
| `workflow.cron-enabled` | `true` \| `false` | `true` | Master switch for cron-scheduled process starts |
| `workflow.outbox-poll-interval-ms` | ms | `500` | How long a relay waits before looking again **when nothing woke it**. A pod raises a signal after committing an outbox row of its own, so this is the fallback for rows written by other pods — a crashed pod's undelivered work, mostly — and no longer the latency every step pays. Before the signal it set per-transition latency directly: 500 ms here meant ~500 ms per transition |
| `workflow.outbox.relay-enabled` | `true` \| `false` | `true` | Enables the outbox relay (set to `false` to disable event relaying entirely) |
| `workflow.consumer.process-parallelism` | int | `1` | How many of a poll batch's per-process groups a pod may commit at once. `1` (the default, the original behaviour) commits them one after another on the consumer thread, so a pod's own parallelism is its partition count; above 1 hands the batch's *independent* processes to a small pool, letting a pod work more processes than it owns partitions. Only ever parallel **across distinct processes** — a single process's events share a partition and stay in one ordered group drained by one thread — so ordering and single-writer are unchanged. Each concurrent process commits in its own transaction, so keep this at or below `DB_POOL_SIZE`. A modest, situational lever (see [Performance](/guides/performance/)): it helps when a pod owns few partitions relative to the work per batch. Pass as a JVM `-D`; the dashed name does not survive env-var relaxed binding |
| `workflow.process-lock-timeout-seconds` | s | `10` | How long to wait for exclusive access to a process before giving up. Exclusivity is a row lock on the process, so this is a statement timeout on the wait, not a spin: the caller queues in the database and is woken in turn |
| `workflow.outbox.batch-size` | int | `100` | How many messages a relay claims per batch. In `kafka` mode the claim holds a row lock on each until the batch is published, so this also bounds how long one pod can keep another from those rows; raising it trades that for fewer round trips |
| `workflow.outbox.relay-concurrency` | int | `0` | How many partition keys the relay publishes at once. `0` is one per key in the batch — bounded by `batch-size`, which is the most a pass can claim. `1` sends inline on the relay thread; any other value is a ceiling. **Sends are on virtual threads**, so a key waiting for its ack costs nothing to have in flight; the number of keys in flight is what lets the Kafka producer build a batched request out of the pass instead of amortising one round trip across a handful of records. Within a key sends stay strictly sequential, so per-process ordering does not depend on this |
| `workflow.outbox.retention` | duration (`24h`, `7d`, `P7D`) | — (off) | How long a **sent** outbox row is kept before it is deleted. Unset means nothing is ever deleted, which is the historical behaviour: every transition of every process leaves its events in that table for good — order of 25 rows per process instance, so millions of processes leave hundreds of millions of rows that autovacuum keeps rewriting and every index scan pays for. `Pending` and `Error` rows are never touched at any setting: one is undelivered work, the other is a parked message waiting for a human |
| `workflow.outbox.purge-batch-size` | int | `1000` | Rows deleted per statement. Bounded so the delete never holds a lock long enough to matter to the relays sharing that database |
| `workflow.outbox.purge-interval` | ISO-8601 duration | `PT1M` | How often a purge pass runs |
| `workflow.outbox.purge-max-batches-per-pass` | int | `20` | Batches one pass may delete. Caps how much a pod that has been down while the table grew can do in one go, so catching up happens over several passes instead of one sweep competing with the engine |
| `workflow.metrics.gauge-ttl` | duration | `PT30S` | How long the two counting gauges (`eventconductor.process.running`, `eventconductor.outbox.pending`) may reuse their last value. Both answer with a `count(*)` against the engine's own database, and counting running processes walks one index entry per running process — the very number being reported — so without this the cost of observing the system grows with the system, once per pod per scrape. `PT0S` restores a query on every scrape |
| `workflow.message-api.api-key` | string | — | API key required in the `X-Api-Key` header of the engine's REST API (`/workflow/api/**`: messages, events, triggers, schedules, sync invocations). See [HTTP security](#http-security) for what happens when it is blank |
| `workflow.message-api.allow-anonymous` | boolean | `false` | Accept requests without credentials when no `api-key` is set and no Spring Security filter chain protects the application. Off since 2.27.0; turn it on only for an isolated deployment. Since 2.28.0 it also lets in unsigned git-import webhooks (no `webhook-secret`) |
| `workflow.rest.max-body-bytes` | bytes | `16777216` (16 MiB) | Request bodies larger than this are cut off unread on `/workflow/api/*` and the git webhooks (`/workflow/webhooks/*`, `/forms/webhooks/*`, `/rules/webhooks/*` — also in the standalone forms and rules apps). Far above the engine's own input limits on purpose: those answer with a message naming the field; this only stops a body too large to read |
| `workflow.security.flow-authorization.enabled` | boolean | `false` | Enforce definitions' `requiredScopes` / `requiredRoles` against the caller (fail closed: a caller nobody identified holds nothing). Also checked when someone creates or edits a schedule or event trigger, or runs a schedule now |
| `workflow.security.trust-forwarded-token` | boolean | `false` | Read the caller's identity from a bearer token this application did **not** verify. Only behind something that did (a gateway, a mesh) — on its own it believes whatever the caller typed. The application's own Spring Security authentication is always preferred |
| `workflow.security.claims.subject` / `.scopes` / `.roles` | csv | `preferred_username,sub` / `scope,scp` / `realm_access.roles,roles` | The token claims a forwarded token's caller id, scopes and roles are read from (first match wins; dotted names reach into nested objects) |
| `workflow.stalled-step-after-ms` / `workflow.stalled-step-scan-interval-ms` | ms | `900000` / `60000` | How long a live ACTION/RULE step with no deadline may wait before `eventconductor.steps.stalled` counts it, and how often that is checked |
| `workflow.stalled-process-after-ms` / `workflow.stalled-process-scan-interval-ms` | ms | `1800000` / `300000` | How long a RUNNING process with no step left to run may sit before it is logged (WARN, with up to 20 ids) and counted by `eventconductor.processes.stalled`, and how often that is checked |
| `workflow.analytics.rollup` | boolean | `false` | `jpa` persistence: keep the analytics read model (daily rollup tables) current in the background instead of computing reports from the raw tables. Each finished process and step is counted once, even across retries and restarts |
| `workflow.analytics.rollup-interval-ms` | ms | `15000` | How often the rollup folds new facts |
| `workflow.analytics.rollup-batch-size` | int | `1000` | Rows read per batch |
| `workflow.analytics.rollup-max-batches-per-cycle` | int | `200` | Batches per stream per cycle — bounds a first-run backfill, which then catches up over several cycles |
| `workflow.analytics.rollup-ceiling-seconds` | s | `2` | Rows newer than this are left for the next cycle, so a commit in flight at the boundary is not missed |
| `workflow.embedded.worker-threads` | int | `0` | Embedded mode only. `0` runs the worker on the thread that dispatched the task — which under `jpa` persistence is the single outbox relay thread, the one advancing **every** process in the JVM, so a worker that blocks stops all of them. Above zero the task is handed to a pool of that many threads and the dispatching thread is freed. See the note below before turning it on |
| `workflow.embedded.worker-queue-capacity` | int | `1000` | How many tasks may wait for a worker thread. A full queue rejects, and the rejection is retryable: the outbox holds the message and offers it again |
| `workflow.embedded.worker-shutdown-grace-ms` | ms | `10000` | How long shutdown waits for in-flight workers before abandoning them |
| `workflow.dynamic.max-steps-per-process` | int | `500` | Runaway guard for [dynamic workflows](/guides/dynamic-workflows/): the most step executions any one process may ever hold, runtime injections included. An injection that would push a process past this cap is rejected and its `DYNAMIC` step fails. A definition's own `maxSteps` overrides this when it declares one (`> 0`); this is the fallback for every definition that does not |
| `rules.persistence` | `jpa` \| `memory` | `memory` | Rule catalog persistence mode |
| `rules.source` | `local` \| `classpath` \| `rest` \| `grpc` | `local` | Where the rule runtime reads rules from |
| `rules.catalog.url` | URL | — | Catalog base URL for `rules.source=rest` |
| `rules.catalog.grpc-target` | `host:port` | — | Catalog gRPC target for `rules.source=grpc` |
| `rules.cache.ttl` | ISO-8601 duration | `PT5M` | Remote rule cache TTL (`PT0S` = never expires) |
| `rules.kafka-refresh` | `true` \| `false` | `false` | Refresh the rule cache from `RulePublished`/`RuleDeleted` events |
| `rules.grpc.enabled` | `true` \| `false` | `false` | Start the catalog's gRPC read API. Plaintext and unauthenticated: anyone reaching the port reads every rule — enable it only behind mTLS (service mesh) and a network policy |
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
| `workflow.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Without it (since 2.28.0) an unsigned webhook is refused with `401` while repositories are configured, unless Spring Security protects the application or `workflow.message-api.allow-anonymous=true`. |

The webhook endpoint is `POST /workflow/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and archives definitions removed from the repo. See [Workflow Definitions — Importing from Git](/guides/workflow-definitions/#importing-from-git) for setup instructions.

## Git import — Forms engine (`forms.git-import.*`)

Clones Git repositories at startup and imports form definition files (`.json`, `.yaml`, `.yml`). Provided by the `forms-engine` module.

| Property | Default | Description |
|---|---|---|
| `forms.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `forms.git-import.repositories[].branch` | `main` | Branch to check out |
| `forms.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `forms.git-import.repositories[].password` | — | Password or personal access token |
| `forms.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Without it (since 2.28.0) an unsigned webhook is refused with `401` while repositories are configured, unless Spring Security protects the application or `workflow.message-api.allow-anonymous=true`. |

The webhook endpoint is `POST /forms/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and deletes forms removed from the repo. See [Form Definitions — Importing from Git](/guides/form-definitions/#importing-from-git) for setup instructions.

## Git import — Rule engine (`rules.git-import.*`)

Clones Git repositories at startup and imports rule definition files (`.json`, `.yaml`, `.yml`). Provided by the `rule-engine` module.

| Property | Default | Description |
|---|---|---|
| `rules.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `rules.git-import.repositories[].branch` | `main` | Branch to check out |
| `rules.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `rules.git-import.repositories[].password` | — | Password or personal access token |
| `rules.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Without it (since 2.28.0) an unsigned webhook is refused with `401` while repositories are configured, unless Spring Security protects the application or `workflow.message-api.allow-anonymous=true`. |

The webhook endpoint is `POST /rules/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`); it reloads only the pushed repository/branch and deletes rules removed from the repo. See [Rule Definitions — Git import](/guides/rule-definitions/#git-import) for setup instructions.

## Task contracts import (`tasks.*`)

Imports [task contracts](/reference/versioning/#task-contracts) (`.ectask`, and `.json`/`.yaml`/`.yml`) from the classpath, local directories and Git — alongside workflows, forms and rules, and **ahead of them**, so a workflow step's `task: <id>` resolves to the latest contract version as the definition loads. Contracts are versioned and **append-only**: nothing is pruned, because a version an in-flight process is pinned to must stay resolvable.

| Property | Default | Description |
|---|---|---|
| `tasks.directory-import.directories[]` | — | Local directories to scan for `.ectask` contracts. |
| `tasks.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH). |
| `tasks.git-import.repositories[].branch` | `main` | Branch to check out. |
| `tasks.git-import.repositories[].directory` | — | Subdirectory to scan (relative to the repo root). Leave blank to scan the whole clone. |
| `tasks.git-import.repositories[].username` | — | Username for HTTPS authentication. |
| `tasks.git-import.repositories[].password` | — | Password or personal access token. |
| `tasks.git-import.webhook-secret` | — | Secret for verifying push webhooks (GitHub/Bitbucket HMAC, GitLab/generic token). Used only when `workflow.git-import.webhook-secret` is blank. With both blank, an unsigned webhook is refused (`401`) unless Spring Security protects the application or `workflow.message-api.allow-anonymous=true`. |

Task git import runs at startup and on a **push webhook**: the workflow engine's `POST /workflow/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`) reloads the pushed repository/branch from the `tasks.git-import` repositories as well as the `workflow.git-import` ones — contracts first, then workflows, so a step's `task:` reference resolves. Nothing is pruned (contracts are append-only). Contracts also load from `classpath:/tasks/` (memory mode serves them from the heap; jpa mode imports them into the database at startup).

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

:::caution[MariaDB/MySQL and Oracle: the migrations are PostgreSQL/H2 only]
The engines' Flyway migrations are written for **PostgreSQL** and **H2** (the engine ships `flyway-database-postgresql` only). On MariaDB/MySQL or Oracle set `workflow.schema.enabled=false` (and `forms.schema.enabled=false`, `rules.schema.enabled=false` for those engines) and let `spring.jpa.hibernate.ddl-auto=update` create the tables — then create the indexes yourself, taking the `CREATE INDEX` statements from `db/migration/workflow` in the `workflow-engine` jar, since `ddl-auto` emits none.
:::

Embedding the engine needs `org.flywaydb:flyway-core` on the classpath (plus the driver module, e.g. `flyway-database-postgresql`) — it is an optional dependency, since a run with `workflow.persistence=memory` needs neither. With `persistence=jpa` and no Flyway the engine warns loudly at startup rather than running indexless in silence.

The standalone apps keep their historical history-table names (`flyway_schema_history`, `…_forms`, `…_rules`) via `FLYWAY_TABLE`, so an existing deployment upgrades in place.

### Distributed locking

How the engine keeps two pods off the same process depends on the mode:

- **`workflow.mode=kafka`** — no per-process lock. Events are keyed by process, so a consumer group hands each process's partition to exactly one orchestrator: a process has a single writer by construction. An optimistic-locking `version` on the `process_entity` and `step_execution_entity` aggregates fences the brief window a Kafka rebalance leaves uncovered (the outgoing pod finishing a record the incoming one now owns) and worker replies that arrive unkeyed — a stale write is rejected instead of overwriting the new owner's work.
- **`workflow.mode=embedded` + `workflow.persistence=jpa`** — a **row lock**. The action runs in a transaction that opens with `SELECT … FOR UPDATE` on the process row and releases on commit; waiting is bounded by `workflow.process-lock-timeout-seconds` (default `10`). Embedded pods share no partitioning, so this is what keeps two of them off the same process. No separate connection and no watchdog are involved.
- **`workflow.mode=embedded` + `workflow.persistence=memory`** — an in-JVM lock (single process only).

Separately, the **singleton background jobs** — the timeout/timer scan, cron-scheduled starts, the embedded outbox relay, and the serialization-lock lease reaper (below) — take a short-lived **database advisory lock** so only one pod runs each. The lock dialect is auto-detected from the JDBC connection:

| Database | Lock mechanism |
|---|---|
| PostgreSQL | `pg_try_advisory_lock(bigint)` / `pg_advisory_unlock(bigint)` |
| MariaDB / MySQL | `GET_LOCK(name, 0)` / `RELEASE_LOCK(name)` |
| Oracle | `DBMS_LOCK.REQUEST` / `DBMS_LOCK.RELEASE` (via PL/SQL) |

These locks are held only for the length of one scan and are session-scoped, so a crashed pod's locks are released automatically by the database when its JDBC connection closes — no watchdog needed.

### Serialization locks (`workflow.lock.*`)

A definition can serialize processes by a key — [`LOCK`/`UNLOCK`](/reference/step-types/#lock) steps around a critical section, or a process-level `processLock`. The held lock is a row keyed by `(name, key)`; whoever cannot take it waits its turn (FIFO). A **lease** bounds how long a hold survives if the process that took it dies without releasing — the crash backstop — and a reaper frees expired holds and admits the next waiter.

| Property | Default | Description |
|---|---|---|
| `workflow.lock.lease-ms` | `900000` | How long a hold lasts (ms) before the reaper may evict it. Generous by design — long enough that a merely-slow holder is never evicted. |
| `workflow.lock.lease-scan-interval-ms` | `60000` | How often (ms) the lease reaper scans for expired holds (jpa mode; one pod at a time via an advisory lock). |
| `workflow.lock.wait-timeout-seconds` | `10` | How long an acquire/release waits for the per-key row lock before giving up (jpa mode). |

Only relevant to definitions that declare locks; one that uses none pays nothing. See [Step types — LOCK / UNLOCK](/reference/step-types/#lock).

### Synchronous invocation (`workflow.sync.*`)

Only relevant to definitions that declare `syncInvocation`; see [Synchronous Invocation](/guides/synchronous-invocation/).

| Property | Default | Description |
|---|---|---|
| `workflow.sync.default-deadline-ms` | `5000` | How long a caller waits when neither it (`Prefer: wait=N`) nor the definition says. |
| `workflow.sync.max-deadline-ms` | `30000` | The longest any caller may wait. |
| `workflow.sync.max-waiting` | `200` | Callers and progress streams waiting per node; past it, 429. |
| `workflow.sync.poll-interval-ms` | `250` | How often a node polls for replies recorded elsewhere — the fallback when NOTIFY is unavailable. |
| `workflow.sync.retention` | `PT24H` | How long an invocation (and so its idempotency key) is remembered. |
| `workflow.sync.purge-interval` | `PT10M` | How often expired invocations are forgotten. |
| `workflow.sync.max-reply-bytes` | `262144` | Largest reply a `REPLY` step may record; larger fails the step. |
| `workflow.sync.stream-interval-ms` | `200` | How often a progress stream (SSE) looks at its process. |
| `workflow.sync.inline.enabled` | `true` | The fast path: drive a synchronously invoked process on the node that received it (jpa). |
| `workflow.sync.inline.threads` | `8` | Processes driven inline at once per node; beyond, the normal path. Keep well under the pool size. |
| `workflow.sync.inline.max-steps` | `64` | Outbox messages per inline drive before the rest goes to the relay. |
| `workflow.sync.inline.claim-lease-ms` | `30000` | How long an inline claim survives a dead node before the sweeper hands it back. |
| `workflow.sync.inline.sweep-interval-ms` | `5000` | How often lapsed inline claims are handed back. |
| `workflow.sync.notify.enabled` | `true` | PostgreSQL `LISTEN/NOTIFY` wake-up across nodes (needs a direct connection, not PgBouncer in transaction mode). |

### Domain events (`workflow.events.*`)

| Property | Default | Description |
|---|---|---|
| `workflow.events.destinations.<name>.topic` | — | The topic a `PUBLISH_EVENT` destination is published to (kafka mode). |
| `workflow.events.destinations.<name>.format` | `binary` | `binary` / `structured` CloudEvents, or `plain`; a step may override. |
| `workflow.events.max-payload-bytes` | `262144` | Largest event payload; larger fails the step. |
| `workflow.events.loopback` | `true` | Embedded mode: a `PUBLISH_EVENT` of this engine is also received by its own event triggers and `onEvent` waits. |
| `workflow.events.consumer.enabled` | `true` | Kafka mode, with `inbound-events-kafka` on the classpath: consume the topics of the destinations event triggers and `onEvent` waits name. |
| `workflow.events.consumer.group` | `eventconductor-inbound-events` | Their consumer group — one per deployment, so each event is used by one pod. |
| `workflow.events.consumer.bootstrap-servers` | — | Brokers; empty means `spring.cloud.stream.kafka.binder.brokers`, else `spring.kafka.bootstrap-servers`. |
| `workflow.events.consumer.auto-offset-reset` | `latest` | Where a new group starts; `latest` does not replay a topic's history when a trigger is turned on. |
| `workflow.events.consumer.refresh-interval` | `PT30S` | How often the set of topics is recomputed from the definitions. |
| `workflow.events.consumer.retry-backoff` | `PT5S` | Back-off before retrying an event whose starts or messages could not be handed over. |
| `workflow.events.consumer.concurrency` | `1` | Consumer threads. |
| `workflow.events.receipts.retention` | `P7D` | How long an event's receipts — the protection against a redelivery — are kept. |
| `workflow.events.receipts.purge-interval` | `PT10M` | How often old receipts are purged. |

The `workflow.events.consumer.*` properties take effect only with the `io.mateu.workflow:inbound-events-kafka` module on the classpath — it holds the Kafka consumer of domain events. `orchestrator-standalone-app` includes it; an application embedding the engine in kafka mode adds it to receive domain events over Kafka.

See [Starting on and waiting for domain events](/guides/domain-event-triggers/).

### Webhook gateway (`workflow.webhooks.*`)

Read by the `webhook-gateway` module (`webhook-gateway-standalone-app`, or any application embedding it). See [Webhooks](/guides/webhooks/).

| Property | Default | Description |
|---|---|---|
| `workflow.webhooks.enabled` | `true` | `false` leaves the endpoints unmapped. |
| `workflow.webhooks.path` | `/webhooks` | The path the endpoints hang from: `POST <path>/<endpoint>`. |
| `workflow.webhooks.transport` | `kafka` | `kafka`: binary CloudEvents on the destination's topic (`workflow.events.destinations.<name>.topic`); `rest`: the engine's `POST /workflow/api/events/{destination}`. |
| `workflow.webhooks.engine.url` | — | The engine's base URL, for the `rest` transport (e.g. `http://orchestrator:8080`). |
| `workflow.webhooks.engine.api-key` | — | Its `X-Api-Key`, when the message API requires one. |
| `workflow.webhooks.engine.timeout` | `PT10S` | How long a hand-over to the engine may take. |
| `workflow.webhooks.kafka.bootstrap-servers` | — | Brokers, for the `kafka` transport; empty means `spring.kafka.bootstrap-servers`. |
| `workflow.webhooks.kafka.send-timeout` | `PT10S` | How long a send may take before the webhook is answered with an error. |
| `workflow.webhooks.endpoints.<name>.provider` | `generic` | `github` or `generic`. `<name>` is the last path segment of the endpoint's URL. |
| `workflow.webhooks.endpoints.<name>.destination` | — | The logical destination the event is published on (never a topic). |
| `workflow.webhooks.endpoints.<name>.secret` | — | The shared secret the sender signs with. Required for GitHub; optional for a generic sender. |
| `workflow.webhooks.endpoints.<name>.source` | the endpoint's name | The CloudEvents `source`. |
| `workflow.webhooks.endpoints.<name>.type-prefix` | `com.github.` | GitHub: the type prefix (`com.github.push`, `com.github.pull_request.opened`). |
| `workflow.webhooks.endpoints.<name>.signature-header` | `X-Signature-256` | Generic: the header carrying the signature (hex HMAC-SHA256 of the body, `sha256=` prefix tolerated). |
| `workflow.webhooks.endpoints.<name>.type-header` | `CE-Type` | Generic: the header carrying the CloudEvents type; absent or empty, `type`. |
| `workflow.webhooks.endpoints.<name>.type` | — | Generic: the type when no header carries one. |
| `workflow.webhooks.endpoints.<name>.id-header` | `CE-Id` | Generic: the header carrying the event id; absent, a random UUID (every delivery is a new event). |
| `workflow.webhooks.endpoints.<name>.subject-header` | `CE-Subject` | Generic: the header carrying the subject (also the record key); optional. |

The gateway reads the destinations' topics from the same `workflow.events.destinations.<name>.topic` keys as the engine, so one set of environment variables names the topics for every component.

### HTTP calls (`workflow.http.*`, in whatever runs `worker-http`)

| Property | Default | Description |
|---|---|---|
| `workflow.http.enabled` | `true` | Register the built-in `http-call@1` task. |
| `workflow.http.topic` | `http-calls` | The Kafka topic the task is served on. |
| `workflow.http.connections.<name>.base-url` | — | Base URL of a named connection. |
| `workflow.http.connections.<name>.auth` | — | Default auth profile for the connection. |
| `workflow.http.connections.<name>.connect-timeout` / `read-timeout` | `PT5S` / `PT30S` | Timeouts for the connection. |
| `workflow.http.connections.<name>.headers.*` | — | Headers added to every call on the connection. |
| `workflow.http.connections.<name>.idempotency-header` | `Idempotency-Key` | Header carrying the step execution id; `none` to omit. |
| `workflow.http.auth.<name>.type` | `none` | `none`, `basic`, `bearer`, `api-key`, `oauth2-client-credentials`. |
| `workflow.http.auth.<name>.*` | — | `username`/`password`, `token`, `header`/`value`/`in`, `token-uri`/`client-id`/`client-secret`/`scope`. |
| `workflow.http.auth.<name>.hosts` | *(none)* | Hosts the profile's credentials may be sent to (`api.x.com`, `*.partner.com`). Without it, the profile is used through a connection, or on an absolute URL only when `allowed-hosts` is set; with it, every target (connections too) must match. A refusal fails the step with `HTTP_FORBIDDEN_AUTH`. |
| `workflow.http.credentials-to-any-host` | `false` | Since 2.28.0, credentials (named profiles, inline `${secret:…}` blocks) are not sent to an absolute URL outside a profile's `hosts` / `allowed-hosts`. `true` restores the earlier behaviour, in which a definition author could send them to any public host. |
| `workflow.http.secrets.<NAME>` | — | What `${secret:NAME}` resolves to (else, with `secrets-from-environment`, the `NAME` property / env var). |
| `workflow.http.secrets-from-environment` | `false` | Whether a `${secret:NAME}` not listed under `workflow.http.secrets` falls back to **any** environment variable or Spring property named `NAME`. Off since 2.27.0, so a definition author cannot reach other values of the process (database passwords, cloud credentials); `true` restores the fallback. While `true` and `allowed-hosts` is empty, a WARN at startup says so. |
| `workflow.http.allowed-hosts` | *(any public host)* | Hosts absolute URLs — and the `token-uri` of an inline OAuth2 block — may target (`api.x.com`, `*.partner.com`). Internal addresses are always refused for them; a host that does not resolve is refused too (a retryable `HTTP_IO`). |
| `workflow.http.connect-timeout` / `read-timeout` | `PT5S` / `PT30S` | Timeouts for absolute URLs. |
| `workflow.http.max-response-bytes` | `1048576` | Largest response read; larger fails the step. |

### LLM calls (`workflow.llm.*`, in whatever runs `worker-llm`)

| Property | Default | Description |
|---|---|---|
| `workflow.llm.enabled` | `true` | Register the built-in `llm-call@1` task. |
| `workflow.llm.topic` | `llm-calls` | The Kafka topic the task is served on. |
| `workflow.llm.connections.<name>.provider` | `anthropic` | `anthropic` or `openai` (OpenAI and any OpenAI-compatible endpoint). Needs `spring-ai-anthropic` / `spring-ai-openai` on the classpath. |
| `workflow.llm.connections.<name>.model` | — | The model steps use unless they name another (e.g. `claude-sonnet-5-5`). |
| `workflow.llm.connections.<name>.api-key` | — | Required. Configuration only — from an env var, a K8s secret, Vault; never in a definition. |
| `workflow.llm.connections.<name>.base-url` | the provider's | A gateway, a proxy or a self-hosted OpenAI-compatible server. |
| `workflow.llm.connections.<name>.temperature` | *(unset)* | Default temperature; unset sends none (current Claude models accept only the default). |
| `workflow.llm.connections.<name>.max-tokens` | `4096` | Default most output tokens. |
| `workflow.llm.connections.<name>.timeout` | `PT60S` | One call's limit; a longer one fails the step as `LLM_IO` (retried). |
| `workflow.llm.connections.<name>.structured-output` | `prompt` | How a `responseSchema` is enforced: `prompt` (in the system prompt, then validated) or `native` (also the provider's structured output). |

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
spring.cloud.function.definition=consumeOutbox;consumeUpstream

# Auto-create topics (dev/test only)
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

With `worker-kafka` on the classpath, `consumeWorkerEvent` is **appended** to that list for you
(merged with whatever the application declares), and its inbound group defaults to
`${spring.application.name:eventconductor-worker}` — so the worker's group follows the
application name unless you set one.

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

# Downstream topic (task execution requests to workers) — the worker's own binding, from worker-kafka
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=${spring.application.name:eventconductor-worker}
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
| `workflow.sharding.placement.datasource.url` / `.username` / `.password` / `.pool-size` | | — / — / — / `4` | The database holding the **placement claims** — which shard each business key is placed on, decided once and synchronously before the creation is published. Usually the same database as the read model, separate pool (this one must be writable). **Setting the URL is what switches the claim on**; without it the ingress router falls back to the eventually-consistent read model and the engine warns at startup that a redelivered creation can be placed a second time. See [placement](/guides/process-index/#placement-the-synchronous-half) |

Each shard is the stock engine re-pointed by config: its own `DB_URL`, per-shard Kafka bindings
(`upstream-<i>`/`downstream-<i>`/`outbox-<i>`/`dead-letter-<i>` via `spring.cloud.stream.bindings.*.destination`),
and two shared topics — `messages`, consumed under a **per-shard consumer group** so every shard sees
every message, and `process-index`, produced to by every shard and consumed by the projector under
**one** group. See the sharded manifests for the full set of overrides.

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

# The engine's own bindings (destinations, a group each, batch-mode) come as defaults;
# worker-kafka appends consumeWorkerEvent and names its group after spring.application.name.
spring.cloud.function.definition=consumeOutbox;consumeUpstream
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

## Observability

Metrics (Micrometer/Prometheus) and distributed tracing (OpenTelemetry/OTLP), including the meter
catalogue for every engine and how to enable Prometheus scraping and OTLP export, are documented on
the dedicated [Observability](/reference/observability/) page.

## HTTP security

**The engine's REST API** (`/workflow/api/**`: messages, events, triggers, schedules, sync invocations) lets a request in when:

1. `workflow.message-api.api-key` is set and the `X-Api-Key` header matches it; or
2. no key is set and a Spring Security filter chain protects the application (the request has passed the application's own authentication — the standalone apps below); or
3. no key is set, no filter chain exists, and `workflow.message-api.allow-anonymous=true`.

Otherwise it answers `401`. Since 2.27.0: before, an engine embedded in an application without Spring Security and without a key answered anybody who could reach the port. At startup the engine logs which of the three applies. A filter chain that permits everything (for instance `eventconductor.security.enabled=false` in the standalone apps) counts as protection — set a key if such an engine is reachable on a network.

Calling it from outside: send `X-Api-Key` when the engine has a key; against the standalone orchestrator (Spring Security, no key) send its HTTP Basic credentials (`eventconductor.security.user` / `.password`) instead.

:::caution[The key covers `/workflow/api/**` only]
`workflow.message-api.*` guards the engine's REST API and nothing else. The admin UI, the MCP endpoints and the rule-read endpoints are **not** covered by it: an engine embedded without Spring Security serves them to anyone who can reach the port. In an embedded deployment that is reachable on a network, protect the application with Spring Security (the standalone apps do, with the basic authentication below).
:::

The standalone apps protect their HTTP endpoints (UI, REST, actuator) with basic authentication, configured via `eventconductor.security.*`:

| Property | Env variable | Default | Description |
|---|---|---|---|
| `eventconductor.security.enabled` | `SECURITY_ENABLED` | `true` | Master switch for HTTP security |
| `eventconductor.security.user` | `SECURITY_USER` | `admin` | Username |
| `eventconductor.security.password` | `SECURITY_PASSWORD` | *(blank)* | Password. If left blank, a random password is generated at startup and — since 2.28.0 — **not** shown, so nobody can log in: set it |

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
| `DB_PASSWORD` | — (no default: set it) | `spring.datasource.password` |
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
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | `management.opentelemetry.tracing.export.otlp.endpoint` |
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
| `DB_PASSWORD` | — (no default: set it) | `spring.datasource.password` |
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
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| `OTLP_METRICS_ENABLED` | `false` | `management.otlp.metrics.export.enabled` — push metrics to a collector as well as exposing them for scraping |
| `OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | `management.otlp.metrics.export.url` |
| `OTLP_METRICS_INTERVAL` | `60s` | `management.otlp.metrics.export.step` |

The **rule-standalone-app** follows the same variable set with `SERVER_PORT` defaulting to `8107`, plus `RULES_PERSISTENCE` (`jpa`), `RULES_GRPC_ENABLED` (`false` since 2.28.0: the gRPC read API is plaintext and unauthenticated — enable it only behind mTLS/a network policy) and `RULES_GRPC_PORT` (`9090`).

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
