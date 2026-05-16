---
title: Configuration Reference
description: Complete reference for all EventConductor configuration properties.
---

## Core properties

| Property | Values | Default | Description |
|---|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `kafka` | Event dispatch mode |
| `workflow.persistence` | `jpa` \| `memory` | `jpa` | State persistence mode |

## Database (when `workflow.persistence=jpa`)

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

The schema is created/updated automatically by Hibernate on startup (`ddl-auto=update`). For production, consider `ddl-auto=validate` with a migration tool (Flyway/Liquibase).

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

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

### Mode: embedded + jpa (PostgreSQL only)

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

### Mode: kafka + jpa (full distributed)

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

## ia-agent-service configuration

See [ia-agent-service](/guides/ia-agent-service/) for the full `application.yaml` reference.

Key environment variables:

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key (required) |
