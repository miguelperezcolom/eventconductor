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

```properties
workflow.mode=embedded
workflow.persistence=memory

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

**Characteristics:**
- Domain events dispatched synchronously on each repository `save()`
- State held in `ConcurrentHashMap` — lost on restart
- Workflow definitions loaded from `classpath:/workflows/*.json` at startup
- No external dependencies

## Mode 2 — Semi-embedded (`embedded` + `jpa`)

No Kafka required. Requires a **supported database** (PostgreSQL, MariaDB/MySQL, or Oracle). Good for **single-node deployments** or development with a real database.

```properties
workflow.mode=embedded
workflow.persistence=jpa

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration

spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

**Characteristics:**
- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table every 5 s)
- All state persisted via JPA/Hibernate
- Survives restarts

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

Each standalone application ships with two Dockerfiles:

| File | Purpose |
|---|---|
| `Dockerfile` | **Local / standalone use.** Multi-stage build: compiles the project with Maven inside the container. Self-contained — no pre-built artifacts needed. |
| `Dockerfile.ci` | **CI/CD use.** Single-stage runner image that copies the jar already built by the pipeline. Avoids re-running Maven (which would fail for local SNAPSHOT dependencies not published to Maven Central). |

Both images are fully configured via environment variables. See the [environment variable reference](/reference/configuration/#docker--environment-variables) for the complete list.

**Build locally (standalone):**

```shell
# from each app directory — builds the project inside Docker
docker build -t orchestrator-standalone-app .
docker build -t forms-standalone-app .
```

**Run (minimal PostgreSQL example):**

```shell
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://db:5432/workflow \
  -e DB_USERNAME=workflow \
  -e DB_PASSWORD=secret \
  -e KAFKA_BROKERS=kafka:9092 \
  orchestrator-standalone-app

docker run -p 8081:8080 \
  -e DB_URL=jdbc:postgresql://db:5432/workflow \
  -e DB_USERNAME=workflow \
  -e DB_PASSWORD=secret \
  -e KAFKA_BROKERS=kafka:9092 \
  forms-standalone-app
```

## CI/CD — publishing to Docker Hub

The **Build and publish** GitHub Actions workflow (`.github/workflows/buid-and-publish.yml`) triggers on every GitHub release and runs these stages in order:

1. Set release version from the Git tag
2. Build all Maven modules (`mvn install`)
3. Publish libraries to Maven Central (`mvn deploy`)
4. Build and push `orchestrator-standalone-app` to Docker Hub
5. Build and push `forms-standalone-app` to Docker Hub

Steps 4 and 5 use `Dockerfile.ci`, which copies the jar produced in step 2 — no Maven re-run inside Docker.

Images are pushed with two tags: the release version and `latest`.

```
<DOCKERHUB_USERNAME>/orchestrator-standalone-app:1.2.3
<DOCKERHUB_USERNAME>/orchestrator-standalone-app:latest

<DOCKERHUB_USERNAME>/forms-standalone-app:1.2.3
<DOCKERHUB_USERNAME>/forms-standalone-app:latest
```

### Required GitHub secrets

Configure these in **Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | Maven Central token username |
| `CENTRAL_TOKEN_PASSWORD` | Maven Central token password |
| `GPG_PRIVATE_KEY` | GPG private key for artifact signing |
| `GPG_PASSPHRASE` | Passphrase for the GPG key |
| `DOCKERHUB_USERNAME` | Docker Hub username |
| `DOCKERHUB_TOKEN` | Docker Hub access token (create at hub.docker.com → Account Settings → Security) |

:::tip
Use a Docker Hub **access token** rather than your account password. Tokens can be scoped to read/write and revoked independently.
:::

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
    build: apps/orchestrator-standalone-app
    ports: ["8105:8080"]
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/workflow
      DB_USERNAME: workflow
      DB_PASSWORD: secret
      KAFKA_BROKERS: kafka:9092
    depends_on: [postgres, kafka]

  forms:
    build: apps/forms-standalone-app
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
| Local development (with DB) | `embedded` + `jpa` |
| Single-node production | `embedded` + `jpa` |
| Multi-node / Kubernetes | `kafka` + `jpa` |
