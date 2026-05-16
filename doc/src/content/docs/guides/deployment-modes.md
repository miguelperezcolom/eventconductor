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

No Kafka required. Requires **PostgreSQL only**. Good for **single-node deployments** or development with a real database.

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
- All state persisted in PostgreSQL via JPA/Hibernate
- Survives restarts

## Mode 3 — Full distributed (`kafka` + `jpa`)

Default mode. Requires a running **Kafka broker** and **PostgreSQL** database. Designed for **production multi-pod** deployments.

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
- State persisted in PostgreSQL
- Multiple orchestrator instances coordinate via **PostgreSQL advisory locks**
- Horizontally scalable — add more orchestrator pods at any time

## Local development with Docker Compose

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

## Choosing a mode

| Scenario | Recommended mode |
|---|---|
| Unit / integration tests | `embedded` + `memory` |
| Local development (no infra) | `embedded` + `memory` |
| Local development (with DB) | `embedded` + `jpa` |
| Single-node production | `embedded` + `jpa` |
| Multi-node / Kubernetes | `kafka` + `jpa` |
