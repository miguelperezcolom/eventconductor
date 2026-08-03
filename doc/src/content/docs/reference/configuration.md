---
title: Configuration Reference
description: Complete reference for all EventConductor configuration properties.
---

## Core properties

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `embedded` | Event dispatch mode |
| `workflow.persistence` | `jpa` \| `memory` | `memory` | Workflow state persistence mode |
| `forms.persistence` | `jpa` \| `memory` | `memory` | Forms state persistence mode |
| `workflow.timeout-scan-interval-ms` | ms | `10000` | How often the scheduler looks for expired step timeouts and due `TIMER` steps. The lookup is an indexed query on the step's materialised deadline, so its cost tracks the work that is due — normally none — and not how many steps are waiting; lowering it tightens firing latency without a scan penalty |
| `workflow.cron-scan-interval-ms` | ms | `10000` | How often the scheduler checks `cronExpression` schedules on ACTIVE definitions |
| `workflow.cron-enabled` | `true` \| `false` | `true` | Master switch for cron-scheduled process starts |
| `workflow.outbox-poll-interval-ms` | ms | `500` | How long a relay waits before looking again **when nothing woke it**. A pod raises a signal after committing an outbox row of its own, so this is the fallback for rows written by other pods — a crashed pod's undelivered work, mostly — and no longer the latency every step pays. Before the signal it set per-transition latency directly: 500 ms here meant ~500 ms per transition |
| `workflow.outbox.relay-enabled` | `true` \| `false` | `true` | Enables the outbox relay (set to `false` to disable event relaying entirely) |
| `workflow.process-lock-timeout-seconds` | s | `10` | How long to wait for exclusive access to a process before giving up. Exclusivity is a row lock on the process, so this is a statement timeout on the wait, not a spin: the caller queues in the database and is woken in turn |
| `workflow.outbox.batch-size` | int | `100` | How many messages a relay claims per batch. In `kafka` mode the claim holds a row lock on each until the batch is published, so this also bounds how long one pod can keep another from those rows; raising it trades that for fewer round trips |
| `workflow.message-api.api-key` | string | — | Optional API key required in the `X-Api-Key` header of `POST /workflow/api/messages`. Blank = unauthenticated |
| `rules.persistence` | `jpa` \| `memory` | `memory` | Rule catalog persistence mode |
| `rules.source` | `local` \| `classpath` \| `rest` \| `grpc` | `local` | Where the rule runtime reads rules from |
| `rules.catalog.url` | URL | — | Catalog base URL for `rules.source=rest` |
| `rules.catalog.grpc-target` | `host:port` | — | Catalog gRPC target for `rules.source=grpc` |
| `rules.cache.ttl` | ISO-8601 duration | `PT5M` | Remote rule cache TTL (`PT0S` = never expires) |
| `rules.kafka-refresh` | `true` \| `false` | `false` | Refresh the rule cache from `RulePublished`/`RuleDeleted` events |
| `rules.grpc.enabled` | `true` \| `false` | `false` | Start the catalog's gRPC read API |
| `rules.grpc.port` | port | `9090` | Catalog gRPC port |

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

The standalone apps ship **Flyway migrations and run them by default** (`FLYWAY_ENABLED=true`). In production also set `DDL_AUTO=validate`, so Flyway owns the schema and Hibernate only checks it.

Do not turn Flyway off. The migrations are the only place the engine's indexes come from: `ddl-auto=update` emits no index DDL at all, so a schema built without them has primary keys and nothing else, and every deadline scan, outbox claim and message correlation becomes a sequential scan. Measured on a cluster where they were missing, PostgreSQL pinned at 750m of CPU and throughput fell to a fraction.

```properties
spring.flyway.enabled=${FLYWAY_ENABLED:true}
spring.flyway.locations=classpath:db/migration/workflow   # forms app: db/migration/forms, rule app: db/migration/rules
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```

The orchestrator ships migrations `V1__baseline.sql` through `V12__optimistic_version.sql`; `baseline-on-migrate` lets Flyway adopt an existing Hibernate-created schema.

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

Consumers are Spring Cloud Function bindings (`<function>-in-0`); producers use the named bindings `outbox`, `upstream`, and `downstream` (published via `StreamBridge`):

```properties
# Outbox topic (internal domain events)
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-group
spring.cloud.stream.bindings.outbox.destination=outbox

# Upstream topic (integration events from external services)
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-group
spring.cloud.stream.bindings.upstream.destination=upstream

# Downstream topic (task execution requests to workers)
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.bindings.downstream.destination=downstream

# Function bindings
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent

# Auto-create topics (dev/test only)
spring.cloud.stream.kafka.binder.auto-create-topics=true
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

spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.bindings.outbox.destination=outbox
spring.cloud.stream.bindings.upstream.destination=upstream
spring.cloud.stream.bindings.downstream.destination=downstream
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
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
| `FLYWAY_ENABLED` | `true` | `spring.flyway.enabled` |
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
| `FLYWAY_ENABLED` | `true` | `spring.flyway.enabled` |
| `DB_POOL_SIZE` | `16` | `spring.datasource.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.datasource.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |
| `SECURITY_ENABLED` | `true` | `eventconductor.security.enabled` |
| `SECURITY_USER` | `admin` | `eventconductor.security.user` |
| `SECURITY_PASSWORD` | *(blank)* | `eventconductor.security.password` |
| `TRACING_SAMPLING` | `0.0` | `management.tracing.sampling.probability` |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | `management.otlp.tracing.endpoint` |

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
