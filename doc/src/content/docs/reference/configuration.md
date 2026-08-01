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
| `workflow.timeout-scan-interval-ms` | ms | `10000` | How often the scheduler scans for expired step timeouts and due `TIMER` steps |
| `workflow.cron-scan-interval-ms` | ms | `10000` | How often the scheduler checks `cronExpression` schedules on ACTIVE definitions |
| `workflow.cron-enabled` | `true` \| `false` | `true` | Master switch for cron-scheduled process starts |
| `workflow.outbox-poll-interval-ms` | ms | `5000` | How often the outbox relay polls the outbox table for pending events |
| `workflow.outbox.relay-enabled` | `true` \| `false` | `true` | Enables the outbox relay (set to `false` to disable event relaying entirely) |
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

The schema is created/updated automatically by Hibernate on startup (`ddl-auto=update`). For production, the standalone apps ship **Flyway migrations** (disabled by default): set `FLYWAY_ENABLED=true` (`spring.flyway.enabled`) together with `DDL_AUTO=validate` so Flyway manages the schema and Hibernate only validates it.

```properties
spring.flyway.enabled=${FLYWAY_ENABLED:false}
spring.flyway.locations=classpath:db/migration/workflow   # forms app: db/migration/forms, rule app: db/migration/rules
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```

The orchestrator ships migrations `V1__baseline.sql` through `V4__workflow_definition_default_max_step_executions.sql`; `baseline-on-migrate` lets Flyway adopt an existing Hibernate-created schema.

### Distributed locking

When `workflow.persistence=jpa`, the engine uses database-level advisory locks to guarantee that only one pod processes a given workflow instance at a time. The lock mechanism is chosen automatically:

| Database | SQL used |
|---|---|
| PostgreSQL | `pg_try_advisory_lock(bigint)` / `pg_advisory_unlock(bigint)` |
| MariaDB / MySQL | `GET_LOCK(name, 0)` / `RELEASE_LOCK(name)` |
| Oracle | `DBMS_LOCK.REQUEST` / `DBMS_LOCK.RELEASE` (via PL/SQL) |

#### Stale lock watchdog

A background daemon thread (`process-lock-watchdog`) runs every **60 seconds** and force-releases any per-process lock that has been held longer than **60 seconds**. Lock-protected operations are expected to complete in milliseconds; the watchdog is a safety net for cases where `unlock()` is never reached (e.g. an unhandled exception or a bug in calling code).

When a stale lock is released the following warning is logged:

```
WARN  JdbcProcessLockService - Releasing stale lock <key> held since <instant> (exceeded 60s threshold)
```

If you see this warning in production, investigate the process that held the lock — it likely indicates an unexpected error in the orchestration flow.

#### Multi-pod safety

The watchdog is safe in multi-pod (Kubernetes) deployments. `heldLocks` is an in-memory map local to each JVM, so each pod's watchdog only sees and releases locks that **the same pod** acquired. It has no visibility into locks held by other pods and cannot interfere with them.

If a pod crashes, no watchdog intervention is needed: advisory locks are session-scoped in all supported databases, so the database releases them automatically as soon as the JDBC connection closes.

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
| `FLYWAY_ENABLED` | `false` | `spring.flyway.enabled` |
| `DB_POOL_SIZE` | `10` | `spring.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |
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
| `FLYWAY_ENABLED` | `false` | `spring.flyway.enabled` |
| `DB_POOL_SIZE` | `10` | `spring.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.hikari.connection-timeout` (ms) |
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
