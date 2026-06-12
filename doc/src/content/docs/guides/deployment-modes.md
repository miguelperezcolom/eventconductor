---
title: Deployment Modes
description: Three deployment modes — from unit tests to multi-pod Kubernetes clusters.
---

EventConductor supports three deployment modes controlled by two independent properties. No code changes are required to switch between them.

| Property | Values | Default |
|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `kafka` |
| `workflow.persistence` | `jpa` \| `memory` | `jpa` |

## Mode 1 — Fully embedded (`embedded` + `memory`)

No Kafka, no database. Everything runs in-process. Ideal for **unit tests**, **local development**, and **embedding in other applications**.

Two working examples are available:
- `demo/embedded` — embedded mode with an HTTP server and MCP endpoint
- `demo/embedded-headless` — embedded mode with **no HTTP server** (pure background process)

```properties
workflow.mode=embedded
workflow.persistence=memory

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.config.BindingServiceConfiguration,\
  org.springframework.cloud.stream.config.BindersHealthIndicatorAutoConfiguration,\
  org.springframework.cloud.stream.config.ChannelsEndpointAutoConfiguration,\
  org.springframework.cloud.stream.config.BindingsEndpointAutoConfiguration,\
  org.springframework.cloud.stream.function.FunctionConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.KafkaStreamsBinderSupportAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.function.KafkaStreamsFunctionAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.ExtendedBindingHandlerMappingsProviderAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.endpoint.KafkaStreamsTopologyEndpointAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.config.ExtendedBindingHandlerMappingsProviderConfiguration,\
  org.springframework.cloud.stream.binder.kafka.config.MessageConverterHelperConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

The application class must scan the engine packages while excluding the JPA-only UI adapters:

```java
@SpringBootApplication
@ComponentScan(
    basePackages = "io.mateu.workflow",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
    )
)
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
- `demo/embedded-db-headless` — embedded + JPA + H2, no HTTP server (pure background process)

```properties
workflow.mode=embedded
workflow.persistence=jpa

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.config.BindingServiceConfiguration,\
  org.springframework.cloud.stream.config.BindersHealthIndicatorAutoConfiguration,\
  org.springframework.cloud.stream.config.ChannelsEndpointAutoConfiguration,\
  org.springframework.cloud.stream.config.BindingsEndpointAutoConfiguration,\
  org.springframework.cloud.stream.function.FunctionConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.KafkaStreamsBinderSupportAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.function.KafkaStreamsFunctionAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.ExtendedBindingHandlerMappingsProviderAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.streams.endpoint.KafkaStreamsTopologyEndpointAutoConfiguration,\
  org.springframework.cloud.stream.binder.kafka.config.ExtendedBindingHandlerMappingsProviderConfiguration,\
  org.springframework.cloud.stream.binder.kafka.config.MessageConverterHelperConfiguration

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

The application class must be in the `io.mateu.workflow` package (or a parent of it), or explicitly configure JPA repository scanning:

```java
@SpringBootApplication
@ComponentScan(
    basePackages = "io.mateu.workflow",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
    )
)
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**Characteristics:**
- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table every 5 s)
- All state persisted via JPA/Hibernate — survives restarts
- Workflow definitions placed in `classpath:/workflows/` are automatically imported into the database at startup by `ClasspathWorkflowDefinitionImporter` (idempotent — skips definitions already present)

## Mode 3 — Full distributed (`kafka` + `jpa`)

Default mode. Requires a running **Kafka broker** and a **supported database** (PostgreSQL, MariaDB/MySQL, or Oracle). Designed for **production multi-pod** deployments.

```properties
# No extra configuration needed — these are the defaults
workflow.mode=kafka
workflow.persistence=jpa

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update

spring.kafka.bootstrap-servers=localhost:9092
```

**Characteristics:**
- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`)
- State persisted in the configured database
- Multiple orchestrator instances coordinate via **distributed advisory locks** (dialect auto-detected from the JDBC connection at startup)
- Horizontally scalable — add more orchestrator pods at any time

## Supported databases (`workflow.persistence=jpa`)

The locking mechanism is automatically selected based on the JDBC driver in use. No extra configuration is required.

| Database | Lock mechanism | Notes |
|---|---|---|
| PostgreSQL | `pg_try_advisory_lock` / `pg_advisory_unlock` | No prerequisites |
| MariaDB / MySQL | `GET_LOCK` / `RELEASE_LOCK` | No prerequisites |
| Oracle | `DBMS_LOCK.REQUEST` / `DBMS_LOCK.RELEASE` | Requires `GRANT EXECUTE ON DBMS_LOCK TO <user>` — see below |
| H2 | In-process `AtomicBoolean` per lock ID | For testing and demos only — single JVM, not distributed |

A background **watchdog thread** runs every 60 s and force-releases any per-process lock held longer than 60 s, preventing connection leaks if a lock is never explicitly released. A `WARN` log entry is emitted when this happens. Each pod's watchdog only operates on locks that pod itself acquired — it cannot affect locks held by other pods. If a pod crashes, the database releases its locks automatically when the JDBC connection closes.

### Oracle prerequisite

Oracle advisory locks are implemented via the `DBMS_LOCK` package, which is not granted by default. A DBA must run the following once per schema user before starting the application:

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

Both images are fully configured via environment variables. See the [environment variable reference](/reference/configuration/#docker--environment-variables) for the complete list.

**Pull and run (minimal PostgreSQL example):**

```shell
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://db:5432/workflow \
  -e DB_USERNAME=workflow \
  -e DB_PASSWORD=secret \
  -e KAFKA_BROKERS=kafka:9092 \
  miguelperezcolom/orchestrator-standalone-app:latest

docker run -p 8081:8080 \
  -e DB_URL=jdbc:postgresql://db:5432/workflow \
  -e DB_USERNAME=workflow \
  -e DB_PASSWORD=secret \
  -e KAFKA_BROKERS=kafka:9092 \
  miguelperezcolom/forms-standalone-app:latest
```

## Local development with Docker Compose

Infrastructure only (run the apps locally with `mvn spring-boot:run`):

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: workflow
      POSTGRES_USER: workflow
      POSTGRES_PASSWORD: secret
    ports: ["5432:5432"]

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    ports: ["9092:9092"]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
```

```shell
docker-compose up -d
mvn spring-boot:run
```

Full stack (infrastructure + app containers):

```yaml
# docker-compose.full.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: workflow
      POSTGRES_USER: workflow
      POSTGRES_PASSWORD: secret

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  orchestrator:
    image: miguelperezcolom/orchestrator-standalone-app:latest
    ports: ["8105:8080"]
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/workflow
      DB_USERNAME: workflow
      DB_PASSWORD: secret
      KAFKA_BROKERS: kafka:9092
    depends_on: [postgres, kafka]

  forms:
    image: miguelperezcolom/forms-standalone-app:latest
    ports: ["8106:8080"]
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/workflow
      DB_USERNAME: workflow
      DB_PASSWORD: secret
      KAFKA_BROKERS: kafka:9092
    depends_on: [postgres, kafka]
```

```shell
docker-compose -f docker-compose.full.yml up -d
```

## Choosing a mode

| Scenario | Recommended mode |
|---|---|
| Unit / integration tests | `embedded` + `memory` |
| Local development (no infra) | `embedded` + `memory` |
| Local development (H2, no infra) | `embedded` + `jpa` (H2) |
| Local development (with real DB) | `embedded` + `jpa` |
| Single-node production | `embedded` + `jpa` |
| Multi-node / Kubernetes | `kafka` + `jpa` |

### Headless vs. HTTP embedded

Both `embedded` variants support running without an HTTP server. Simply use `spring-boot-starter` instead of `spring-boot-starter-web` as your application's base dependency and omit `spring-ai-starter-mcp-server-webmvc`. Spring Boot will detect no servlet container on the classpath and start as a non-web application — the workflow engine runs fully in-process with no open port.

Working headless examples:
- `demo/embedded-headless` — `embedded` + `memory`, no HTTP server
- `demo/embedded-db-headless` — `embedded` + `jpa` (H2), no HTTP server; state survives restarts
