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
spring.jpa.hibernate.ddl-auto=validate
```

Add Flyway to the classpath and the engine applies its own schema at startup:

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

:::caution[Without the migrations the engine has no indexes]
`ddl-auto=update` builds tables and columns and **emits no index DDL at all** — that is a
limitation of Hibernate's update path, not a configuration mistake. The engine's indexes come only
from its migrations, so a schema built by `ddl-auto` alone has primary keys and nothing else, and
every deadline scan, outbox claim and message correlation degrades into a sequential scan. Measured
on a cluster with ~9,000 live step rows, that pinned PostgreSQL at 75% of a core and cut throughput
to a fraction.

The engine ships those migrations in its own jar and runs them itself, into its own history table —
it does not touch `flyway_schema_history`, so your application's migrations are unaffected. All it
needs is `flyway-core` on the classpath; with `persistence=jpa` and no Flyway it warns loudly at
startup rather than running indexless in silence.

It is safe over a schema `ddl-auto` already created: it baselines at 0 and every migration is
written to run over either shape of the schema — `DdlAutoToFlywayUpgradeTest` runs the whole chain
over a Hibernate-built schema on every build, so this is checked rather than promised. Once the
indexes are in place, `ddl-auto=validate` lets the migrations own the schema outright.

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

:::caution[If you wire the bindings yourself]
The engine contributes its own bindings as defaults, so normally you do not have to. If you do,
two things it sets are not optional.

**`consumer.batch-mode=true` on both.** The engine's consumers take a batch of events, so a
binding without it delivers one unconverted record: the payload arrives as a `byte[]`, the first
event dies with `ClassCastException: class [B cannot be cast to class java.util.List`, retries
exhaust, and the outbox is dead-lettered event by event.

**A group each.** Give `consumeUpstream-in-0` and `consumeOutbox-in-0`
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
| `worker` | 8107 | `worker-standalone-app` — the [test worker](/guides/test-worker/); UI at `/_worker` |

To override values (e.g. a different image tag):

```shell
DB_PASSWORD=mypwd docker compose up -d
# or
docker compose up -d --env-file .env.prod
```

## Kubernetes (Helm)

The chart in `charts/eventconductor/` deploys the whole stack — Redpanda, PostgreSQL, the
orchestrator, the forms engine and the rule engine — with PersistentVolumeClaims, Secrets and
readiness-gated initContainers.

**Install:**

```shell
helm install ec charts/eventconductor --set postgres.password=mypwd
```

The password has no default and the chart refuses to render without one, deliberately: the
alternative is a known password on a database reachable from every pod in the namespace. To hold
it outside your shell history, put it in a Secret with the keys `POSTGRES_DB`, `POSTGRES_USER`
and `POSTGRES_PASSWORD` and name it instead:

```shell
helm install ec charts/eventconductor --set postgres.existingSecret=ec-postgres
```

**Key values (`charts/eventconductor/values.yaml`):**

| Value | Default | Description |
|---|---|---|
| `postgres.password` | — | PostgreSQL password. Required unless `postgres.existingSecret` is set |
| `postgres.existingSecret` | `""` | Secret holding `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` |
| `postgres.maxConnections` | `200` | Server connection limit. The three apps ask for 96 of them before anything else connects |
| `postgres.storage` | `8Gi` | PVC size for PostgreSQL |
| `redpanda.defaultTopicPartitions` | `6` | Partitions, which cap how many orchestrator pods can share the load |
| `redpanda.storage` | `8Gi` | PVC size for Redpanda |
| `redpanda.externalNodePort` | `30092` | NodePort for external Kafka access (0 = disabled) |
| `orchestrator.replicas` | `2` | Orchestrator pod count |
| `orchestrator.dbPoolSize` | `16` | Connections per pod. Must cover both consumer bindings, the relay, the scanner and the UI |
| `orchestrator.consumerConcurrency` | `3` | Consumer threads per pod, per binding |
| `orchestrator.extraEnv` | `{}` | Extra environment, injected verbatim (tracing endpoints, git import, …) |
| `forms.replicas` / `rules.replicas` | `2` | Pod counts |
| `*.image` / `*.imageTag` | see values | Image; the tag defaults to the chart's `appVersion` |
| `*.flywayTable` | per app | Migration history table for that app's engine. The three apps share one database and must not share one history table |
| `ingress.enabled` | `false` | Ingress for the three UIs |

**Upgrade / uninstall:**

```shell
helm upgrade ec charts/eventconductor --reuse-values --set orchestrator.replicas=3
helm uninstall ec
```

### The demo

`charts/eventconductor-demo/` deploys the seven services of the [demo](/guides/demos/) — shell,
gateway, users, content, control plane, booking and the AI agent — as ordinary applications that
participate in workflows. It brings no infrastructure of its own: it talks to the PostgreSQL,
Redpanda and orchestrator of an EventConductor release, so install that one first and point this
one at it.

```shell
helm install demo charts/eventconductor-demo \
  --set engine.postgresSecret=ec-postgres
```

The AI agent is the one service that needs something from you — a Claude key, without which it
cannot start at all. Create the Secret and name it:

```shell
kubectl create secret generic ec-demo-anthropic --from-literal=ANTHROPIC_API_KEY=sk-...
helm upgrade demo charts/eventconductor-demo \
  --reuse-values --set services.ia-agent-service.anthropicSecret=ec-demo-anthropic
```

Both charts install into whatever namespace you give them; the demo resolves the engine by service
name, so they have to share one.

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
