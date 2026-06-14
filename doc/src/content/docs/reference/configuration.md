---
title: Configuration Reference
description: Complete reference for all EventConductor configuration properties.
---

## Core properties

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `kafka` | Event dispatch mode |
| `workflow.persistence` | `jpa` \| `memory` | `jpa` | State persistence mode |

## Git import (`workflow.git-import.*`)

Clones Git repositories at startup and imports workflow definition files (`.json`, `.yaml`, `.yml`). Only available when `workflow.persistence=jpa`.

| Property | Default | Description |
|---|---|---|
| `workflow.git-import.repositories[].url` | — | Git clone URL (HTTPS or SSH) |
| `workflow.git-import.repositories[].branch` | `main` | Branch to check out |
| `workflow.git-import.repositories[].username` | — | Username for HTTPS authentication |
| `workflow.git-import.repositories[].password` | — | Password or personal access token |
| `workflow.git-import.webhook-secret` | — | HMAC-SHA256 secret for verifying GitHub webhook payloads (`X-Hub-Signature-256`). Leave blank to disable signature verification. |

The webhook endpoint is `POST /workflow/webhooks/github`. See [Workflow Definitions — Importing from Git](/guides/workflow-definitions/#importing-from-git) for setup instructions.

## Application class (embedded modes)

When using `workflow.mode=embedded`, use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication`. It sets up the correct component scan — including the UI exclusion filter — automatically.

**`embedded` + `memory`:**

```java
@WorkflowEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**`embedded` + `jpa`:**

```java
@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@WorkflowEmbeddedApplication` is provided by the `workflow-engine` module. It combines `@SpringBootConfiguration`, `@EnableAutoConfiguration`, and a `@ComponentScan` scoped to `io.mateu.workflow` with the UI adapter layer excluded.

For `workflow.mode=kafka` (the default), use `@SpringBootApplication` as normal — the engine integrates as a regular Spring Boot auto-configuration.

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

The schema is created/updated automatically by Hibernate on startup (`ddl-auto=update`). For production, consider `ddl-auto=validate` with a migration tool (Flyway/Liquibase).

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

```properties
# Outbox topic (internal domain events)
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-group
spring.cloud.stream.bindings.outbox-out-0.destination=outbox

# Upstream topic (integration events from external services)
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-group
spring.cloud.stream.bindings.upstream-out-0.destination=upstream

# Downstream topic (task execution requests to workers)
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.bindings.downstream-out-0.destination=downstream

# Function bindings
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent

# Auto-create topics (dev/test only)
spring.cloud.stream.kafka.binder.auto-create-topics=true
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
spring.cloud.stream.bindings.outbox-out-0.destination=outbox
spring.cloud.stream.bindings.upstream-out-0.destination=upstream
spring.cloud.stream.bindings.downstream-out-0.destination=downstream
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

## Docker / environment variables

Both standalone images are fully configured via environment variables. All variables have defaults so only values that differ from the defaults need to be set.

### orchestrator-standalone-app

| Variable | Default | Maps to |
|---|---|---|
| `SERVER_PORT` | `8080` | `server.port` |
| `WORKFLOW_MODE` | `kafka` | `workflow.mode` |
| `WORKFLOW_PERSISTENCE` | `jpa` | `workflow.persistence` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/workflow` | `spring.datasource.url` |
| `DB_USERNAME` | `workflow` | `spring.datasource.username` |
| `DB_PASSWORD` | `secret` | `spring.datasource.password` |
| `DB_DRIVER` | `org.postgresql.Driver` | `spring.datasource.driver-class-name` |
| `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | `spring.jpa.database-platform` |
| `DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto` |
| `DB_POOL_SIZE` | `10` | `spring.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |

### forms-standalone-app

| Variable | Default | Maps to |
|---|---|---|
| `SERVER_PORT` | `8080` | `server.port` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/workflow` | `spring.datasource.url` |
| `DB_USERNAME` | `workflow` | `spring.datasource.username` |
| `DB_PASSWORD` | `secret` | `spring.datasource.password` |
| `DB_DRIVER` | `org.postgresql.Driver` | `spring.datasource.driver-class-name` |
| `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | `spring.jpa.database-platform` |
| `DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto` |
| `DB_POOL_SIZE` | `10` | `spring.hikari.maximum-pool-size` |
| `DB_CONNECTION_TIMEOUT` | `20000` | `spring.hikari.connection-timeout` (ms) |
| `KAFKA_BROKERS` | `localhost:9092` | `spring.cloud.stream.kafka.binder.brokers` |

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
