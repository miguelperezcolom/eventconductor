---
title: Deployment Modes
description: Three deployment modes — from unit tests to multi-pod Kubernetes clusters.
---

EventConductor supports three deployment modes controlled by two independent properties. No code changes are required to switch between them.

| Property | Values | Default |
|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `embedded` |
| `workflow.persistence` | `jpa` \| `memory` | `memory` |

## Mode 1 — Fully embedded (`embedded` + `memory`)

Default mode. No Kafka, no database. Everything runs in-process. Ideal for **unit tests**, **local development**, and **embedding in other applications**.

Two working examples are available:
- `testbench/workflow-embedded` — embedded mode with an HTTP server and MCP endpoint
- `testbench/workflow-embedded-headless` — embedded mode with **no HTTP server** (pure background process)

```properties
workflow.mode=embedded
workflow.persistence=memory
```

> Kafka and JPA autoconfiguration classes are excluded **automatically** by `workflow-engine` when `workflow.mode=embedded`. No manual `spring.autoconfigure.exclude` configuration is needed.

Use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication`. It automatically excludes the UI adapter layer (which requires a web context and JPA) from component scanning:

```java
@WorkflowEmbeddedApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**Characteristics:**
- Domain events dispatched synchronously on each repository `save()`
- State held in `ConcurrentHashMap` — lost on restart
- Workflow definitions loaded from `classpath:/workflows/` at startup (`.json`, `.yaml`, `.yml`)
- No external dependencies

## Mode 2 — Semi-embedded (`embedded` + `jpa`)

No Kafka required. Requires a **supported database**. Good for **single-node deployments**, development with a real database, or demos with an in-memory H2 DB.

A working example is available:
- `testbench/workflow-embedded-db-headless` — embedded + JPA + H2, no HTTP server (pure background process)

```properties
workflow.mode=embedded
workflow.persistence=jpa

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
spring.flyway.enabled=true
```

:::caution[Run the migrations, or the engine has no indexes]
`ddl-auto=update` builds tables and columns and **emits no index DDL at all** — that is a
limitation of Hibernate's update path, not a configuration mistake. The engine's indexes live in
the Flyway migrations, so a schema built by `ddl-auto` alone has primary keys and nothing else, and
every deadline scan, outbox claim and message correlation degrades into a sequential scan. Measured
on a cluster with ~9,000 live step rows, that pinned PostgreSQL at 75% of a core and cut throughput
to a fraction.

Enable Flyway (`spring.flyway.enabled=true`), which the standalone app and the Helm chart now do by
default. It is safe over a schema `ddl-auto` already created: it baselines at V1 and every later
migration is written to run over either shape of the schema — `DdlAutoToFlywayUpgradeTest` runs the
whole chain over a Hibernate-built schema on every build, so this is checked rather than promised.
Once the indexes are in place, move to `ddl-auto=validate` and let the migrations own the schema.

If your database already ran the migrations under 1.0-beta.015 or earlier, `V11` was corrected
after the fact and its checksum changed, so run `flyway repair` once before starting the new
version. A database that never got past `V11` — it failed there — needs nothing.

`ddl-auto=update` on its own is fine for a throwaway database and for tests. It is not a way to run
this engine in production.
:::

> Kafka autoconfiguration classes are excluded **automatically** by `workflow-engine` when `workflow.mode=embedded`.

Use `@WorkflowEmbeddedApplication` (which handles component scanning) plus `@EnableJpaRepositories` and `@AutoConfigurationPackage` pointing at the engine's persistence package, so Spring Data JPA and Hibernate find the repositories and entities regardless of where your main class lives:

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

**Characteristics:**
- Events dispatched in-process via `EmbeddedOutboxRelay`. It is woken by the pod's own writes; the poll interval is a fallback, not the latency of a step
- All state persisted via JPA/Hibernate — survives restarts
- Workflow definitions placed in `classpath:/workflows/` are automatically imported into the database at startup by `ClasspathWorkflowDefinitionImporter` (idempotent — skips definitions already present)

## Mode 3 — Full distributed (`kafka` + `jpa`)

Requires a running **Kafka broker** and a **supported database** (PostgreSQL, MariaDB/MySQL, or Oracle). Designed for **production multi-pod** deployments.

```properties
workflow.mode=kafka
workflow.persistence=jpa

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true

spring.kafka.bootstrap-servers=localhost:9092
```

**Characteristics:**
- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`)
- State persisted in the configured database
- **One pod owns each process.** Every event carries its process as the Kafka message key, so all
  of a process's events land on one partition and a consumer group gives that partition to exactly
  one consumer. Nothing coordinates; an optimistic version on the process fences the brief window
  of a rebalance
- Horizontally scalable — add more orchestrator pods at any time

:::caution[One consumer group per binding]
If you wire the bindings yourself, give `consumeUpstream-in-0` and `consumeOutbox-in-0`
**different** groups. A group whose members subscribe to different topics is assigned by Kafka's
default range assignor per topic, and with mixed subscriptions it leaves partitions with **no
consumer at all** — those events are never read and the processes waiting on them never move.
Observed on a live cluster: 12 members holding 7 of 12 partitions, throughput at a quarter of what
the same pods managed once the groups were split. The shipped configuration uses
`orchestrator-upstream` and `orchestrator-outbox`.
:::

## Supported databases (`workflow.persistence=jpa`)

Two things need coordinating across pods, and they are not the same thing.

**Per-process exclusion.** In `kafka` mode nothing does it: the partition already guarantees a
single writer, and an optimistic version on the process and its steps rejects the stale write a
rebalance can produce. In `embedded` mode there are no partitions, so a row lock on the process
(`SELECT … FOR UPDATE`, held by the transaction the work already runs in) keeps two pods apart.
Neither needs configuration.

**Singleton background jobs** — the timeout scan, cron-scheduled starts and the embedded relay —
still take a database advisory lock so only one pod runs each. That is what the table below
selects, automatically, from the JDBC driver in use.

| Database | Lock mechanism | Notes |
|---|---|---|
| PostgreSQL | `pg_try_advisory_lock` / `pg_advisory_unlock` | No prerequisites |
| MariaDB / MySQL | `GET_LOCK` / `RELEASE_LOCK` | No prerequisites |
| Oracle | `DBMS_LOCK.REQUEST` / `DBMS_LOCK.RELEASE` | Requires `GRANT EXECUTE ON DBMS_LOCK TO <user>` — see below |
| H2 | In-process `AtomicBoolean` per lock ID | For testing and demos only — single JVM, not distributed |

These locks are held only for the length of one scan, and a crashed pod's locks are released by the
database when its connection closes.

Earlier versions also took an advisory lock per process, which required a watchdog to force-release
locks held too long — and a watchdog that yanks exclusivity from an operation still running is a
hazard of its own. Neither exists now: exclusion is either the partition or the transaction, and
both end on their own.

### Oracle prerequisite

The singleton job locks above are implemented on Oracle via the `DBMS_LOCK` package, which is not granted by default. A DBA must run the following once per schema user before starting the application:

```sql
GRANT EXECUTE ON DBMS_LOCK TO <your_schema_user>;
```

Without this grant the application will fail to acquire locks on startup. The JDBC URL for Oracle follows the standard format:

```properties
spring.datasource.url=jdbc:oracle:thin:@localhost:1521:XE
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.database-platform=org.hibernate.dialect.OracleDialect
```

## Docker images

Pre-built images are published to Docker Hub on every release and are ready to use — no build step required:

| Image | Docker Hub |
|---|---|
| `orchestrator-standalone-app` | `miguelperezcolom/orchestrator-standalone-app` |
| `forms-standalone-app` | `miguelperezcolom/forms-standalone-app` |
| `worker-standalone-app` | `miguelperezcolom/worker-standalone-app` |

All images are fully configured via environment variables. See the [environment variable reference](/reference/configuration/#docker--environment-variables) for the complete list.

## Docker Compose

A ready-to-use `docker-compose.yml` is included in the `apps/` directory. It starts the full stack — Redpanda, PostgreSQL, orchestrator, forms, and worker — with healthcheck-gated startup ordering:

```shell
cd apps
docker compose up -d
```

| Service | Port | Description |
|---|---|---|
| `postgres-db` | 5432 | PostgreSQL (`workflow` database) |
| `redpanda` | 9092 | Kafka-compatible broker (external) |
| `redpanda-console` | 8888 | Redpanda web console |
| `orchestrator` | 8105 | `orchestrator-standalone-app` |
| `forms` | 8106 | `forms-standalone-app` |
| `worker` | 8107 | `worker-standalone-app` |

To override values (e.g. a different image tag):

```shell
DB_PASSWORD=mypwd docker compose up -d
# or
docker compose up -d --env-file .env.prod
```

## Kubernetes (Helm)

A Helm chart is included in the `charts/eventconductor/` directory. It deploys the same stack — Redpanda, PostgreSQL, orchestrator, and forms — with PersistentVolumeClaims, Secrets, and readiness-gated initContainers.

**Install:**

```shell
helm install ec charts/eventconductor
```

**Install with overrides:**

```shell
helm install ec charts/eventconductor \
  --set postgres.password=mypwd \
  --set orchestrator.replicas=2 \
  --set orchestrator.image=miguelperezcolom/orchestrator-standalone-app:1.2.0
```

**Key values (`charts/eventconductor/values.yaml`):**

| Value | Default | Description |
|---|---|---|
| `postgres.password` | `secret` | PostgreSQL password |
| `postgres.storage` | `8Gi` | PVC size for PostgreSQL |
| `redpanda.storage` | `8Gi` | PVC size for Redpanda |
| `redpanda.externalNodePort` | `30092` | NodePort for external Kafka access (0 = disabled) |
| `orchestrator.replicas` | `1` | Orchestrator pod count |
| `orchestrator.image` | `miguelperezcolom/orchestrator-standalone-app:latest` | Image |
| `forms.replicas` | `1` | Forms pod count |
| `forms.image` | `miguelperezcolom/forms-standalone-app:latest` | Image |

**Upgrade / uninstall:**

```shell
helm upgrade ec charts/eventconductor --set orchestrator.replicas=3
helm uninstall ec
```

## Choosing a mode

| Scenario | Recommended mode |
|---|---|
| Unit / integration tests | `workflow-embedded` + `memory` |
| Local development (no infra) | `workflow-embedded` + `memory` |
| Local development (H2, no infra) | `workflow-embedded` + `jpa` (H2) |
| Local development (with real DB) | `workflow-embedded` + `jpa` |
| Single-node production | `workflow-embedded` + `jpa` |
| Multi-node / Kubernetes | `kafka` + `jpa` |

### Headless vs. HTTP embedded

Both `embedded` variants support running without an HTTP server. Simply use `spring-boot-starter` instead of `spring-boot-starter-web` as your application's base dependency and omit `spring-ai-starter-mcp-server-webmvc`. Spring Boot will detect no servlet container on the classpath and start as a non-web application — the workflow engine runs fully in-process with no open port.

Working headless examples:
- `testbench/workflow-embedded-headless` — `embedded` + `memory`, no HTTP server
- `testbench/workflow-embedded-db-headless` — `embedded` + `jpa` (H2), no HTTP server; state survives restarts
