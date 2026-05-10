# EventConductor — Workflow Engine

EventConductor is a production-grade, event-driven workflow orchestration platform for the
Java/Spring ecosystem. It covers the full lifecycle of a business process — from definition
to execution to monitoring — without forcing you into BPMN's complexity or an external
SaaS dependency.

---

## Why EventConductor?

### Distributed by design
EventConductor is built from the ground up for distributed environments. Multiple orchestrator
instances coordinate safely using PostgreSQL advisory locks and the outbox pattern, giving you
horizontal scalability with no single point of failure. Scale from a single JVM to a
multi-pod Kubernetes cluster without changing a line of business code.

### Infinitely scalable
Workers are stateless microservices that subscribe to Kafka topics. Add more worker instances
to handle higher load at any time. The engine never becomes a bottleneck — it delegates all
business logic to workers and simply drives the state machine.

### Event-driven, not polling
The orchestration loop is triggered entirely by domain events. No polling loops, no scheduled
queries that grow with your data. Each state transition is an immutable event stored in the
outbox table and relayed to the appropriate handler, keeping latency low and audit trails
complete.

### A DSL designed for business workflows, not diagrams
BPMN was designed to be drawn, not written. EventConductor's JSON workflow DSL was designed
to be owned by developers: human-readable, version-controlled, reviewable in a PR, and
expressive enough to model retries, timeouts, compensation (saga), parallel execution,
sub-processes, conditional branching (JEXL expressions), and human tasks — all in a single
flat file.

### Built-in forms engine
The `forms-engine` module handles form definitions, validation, and rendering. User-task steps
reference a form by ID; the engine takes care of the rest. Forms are defined in JSON, stored
in version control, and served dynamically to any front-end.

### Visual editors included
- **Drag-and-drop workflow editor** — design and modify workflows visually; changes are
  persisted back as JSON definitions.
- **Drag-and-drop form editor** — build form layouts visually without writing HTML or schemas.

Both editors are included in the platform UI.

### Full management UI
A web UI is provided out of the box for operators and developers:
- Browse and manage workflow definitions
- Monitor running process instances and step executions
- Inspect variables, logs, and audit trail per process
- Trigger and cancel processes manually
- Manage form definitions

### Deploy anywhere, from a unit test to production
Three deployment modes with no code changes:

| Scenario | `workflow.mode` | `workflow.persistence` | External dependencies |
|---|---|---|---|
| Unit tests / embedded library | `embedded` | `memory` | None |
| Single-node with persistence | `embedded` | `jpa` | PostgreSQL only |
| Full distributed / multi-pod | `kafka` | `jpa` | PostgreSQL + Kafka |

### First-class AI integration
The `ia-agent-service` demo ships an LLM-powered agent (Claude / Anthropic) that lets
operators query and control the orchestration engine in natural language via MCP tools.

---

## Repository structure

```
eventconductor/
├── modules/                   Reusable library modules
│   ├── shared/                DTOs, domain events, DDD base classes
│   ├── workflow-engine/       Core orchestration engine (embeddable Spring Boot library)
│   ├── forms-engine/          Form definition and rendering engine
│   └── sample-worker/         Hello-world worker example
└── demo/                      Example microservices that use the modules
    ├── api-gw/                API gateway
    ├── booking-service/       Sample business service (orchestrated)
    ├── content-service/       Sample content service
    ├── control-plane-service/ Orchestrator host application
    ├── ia-agent-service/      AI agent (Claude) with MCP tool integration
    ├── users-service/         User management
    └── static-content-server/ Static asset server
```

---

## Build

```shell
mvn clean install
```

---

## Deployment modes

The engine supports three modes controlled by two independent properties:

| Property | Values | Default |
|---|---|---|
| `workflow.mode` | `kafka` \| `embedded` | `kafka` |
| `workflow.persistence` | `jpa` \| `memory` | `jpa` |

### Mode 1 — Full distributed (`kafka` + `jpa`)

Default mode. Requires a running Kafka broker and PostgreSQL database.

```properties
# No extra configuration needed — these are the defaults
workflow.mode=kafka
workflow.persistence=jpa
```

- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`).
- State persisted in PostgreSQL via JPA/Hibernate.
- Multiple orchestrator instances coordinate via PostgreSQL advisory locks.

### Mode 2 — Semi-embedded (`embedded` + `jpa`)

No Kafka required. Requires PostgreSQL only.

```properties
workflow.mode=embedded
workflow.persistence=jpa

# Exclude Kafka auto-configuration
spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
```

- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table every 5 s).
- All state persisted in PostgreSQL.
- Useful for single-node deployments or local development with a database.

### Mode 3 — Fully embedded (`embedded` + `memory`)

No Kafka, no database. Everything runs in-process.

```properties
workflow.mode=embedded
workflow.persistence=memory

# Exclude Kafka and JPA auto-configuration
spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

- Domain events dispatched synchronously on each repository `save()`.
- State held in `ConcurrentHashMap` (lost on restart).
- Workflow definitions loaded from `classpath:/workflows/*.json` at startup.
- Ideal for tests, local development, and embedding in other applications.

---

## Kafka topics (mode: kafka)

| Topic | Direction | Description |
|---|---|---|
| `outbox` | internal | Domain events relayed from the outbox table |
| `upstream` | inbound | Integration events from external services (process creation, timeouts) |
| `downstream` | outbound | Task execution requests sent to workers |

### Kafka configuration (application.properties)

```properties
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.outbox-out-0.destination=outbox
spring.cloud.stream.bindings.upstream-out-0.destination=upstream
spring.cloud.stream.bindings.downstream-out-0.destination=downstream

spring.kafka.bootstrap-servers=localhost:9092
```

---

## Workflow definitions

### File format (`classpath:/workflows/*.json`)

Used in `memory` persistence mode. Each file defines one workflow.

```json
{
  "id": "my-workflow",
  "name": "My Workflow",
  "version": 1,
  "description": "Optional description",
  "status": "ACTIVE",
  "limitConcurrentExecutions": false,
  "maxConcurrentExecutions": 0,
  "enqueueOnLimit": false,
  "steps": [
    {
      "id": "step-1",
      "type": "ACTION",
      "name": "Do something",
      "topic": "my-worker-topic",
      "timeout": 30000,
      "retries": 2,
      "rollbackable": true,
      "compensationStepId": "step-compensate"
    },
    {
      "id": "step-2",
      "type": "USER_TASK",
      "name": "Human approval",
      "formId": "approval-form",
      "preconditionStepId": "step-1"
    },
    {
      "id": "step-compensate",
      "type": "ACTION",
      "name": "Undo step 1",
      "topic": "my-worker-topic"
    }
  ]
}
```

### Step types

| Type | Description | Required fields |
|---|---|---|
| `ACTION` | Dispatches a task to a worker | `topic` |
| `USER_TASK` | Pauses the workflow for a human form submission | `formId` |
| `PROCESS` | Starts a child workflow as a sub-process | `childWorkflowDefinitionId` |
| `JOIN` | Waits for all parallel branches to complete | — |
| `FORK` | Starts parallel branches | — |
| `END` | Marks the workflow as complete | — |

### Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique identifier within the workflow |
| `type` | enum | — | See step types above |
| `name` | string | — | Human-readable name |
| `description` | string | — | Optional description |
| `preconditionStepId` | string | — | Step that must complete before this one starts |
| `preconditionExpression` | string | — | JEXL expression over process variables; step is skipped if `false` |
| `parallel` | boolean | `false` | Allows concurrent execution with other parallel steps |
| `topic` | string | — | Worker topic/destination (ACTION only) |
| `formId` | string | — | Form identifier (USER_TASK only) |
| `childWorkflowDefinitionId` | string | — | Child workflow ID (PROCESS only) |
| `timeout` | integer (ms) | `0` | Max execution time; `0` = no timeout |
| `retries` | integer | `0` | Auto-retry attempts on ERROR or TIMEOUT |
| `rollbackable` | boolean | `false` | Trigger compensation step on failure |
| `compensationStepId` | string | — | Step to run as compensation (requires `rollbackable: true`) |

### Workflow definition status

| Status | Description |
|---|---|
| `DRAFT` | Under construction, not executable |
| `ACTIVE` | Ready to accept new process instances |
| `DISABLED` | No new instances allowed; running ones continue |
| `ARCHIVED` | Retired definition |

---

## Starting a process

### Via Kafka (mode: kafka)

Send a `ProcessCreationRequested` event to the `upstream` topic:

```json
{
  "@type": "ProcessCreationRequested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" },
    { "name": "amount",  "value": "99.90" }
  ]
}
```

### Programmatically (any mode)

Inject `ProcessUpstreamEventUseCase` and call it directly:

```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested(
        "my-workflow",
        "order-123",
        List.of(
            new Variable("orderId", "123"),
            new Variable("amount",  "99.90")
        )
    )
));
```

---

## Implementing a worker

A worker receives `TaskExecutionRequested` events, performs work, and reports back
`TaskStatusChanged`.

### Kafka worker

Subscribe to the `downstream` topic, process the task, then publish to `upstream`:

```java
// Receive
record TaskExecutionRequested(
    String taskExecutionId,
    String processId,
    String workflowDefinitionId,
    String stepId,
    String taskId,
    List<Variable> variables
) {}

// Report back
record TaskStatusChanged(
    String taskExecutionId,
    TaskStatus status,       // COMPLETED | ERROR | RUNNING
    List<Variable> variables // output variables merged into the process
) {}
```

### Embedded worker (mode: embedded)

Provide a bean implementing `EmbeddedTaskExecutor`:

```java
@Bean
public EmbeddedTaskExecutor myWorker(UpdateStepExecutionUseCase updateStepExecution) {
    return request -> {
        // perform work ...
        updateStepExecution.handle(new UpdateStepExecutionCommand(
            request.taskExecutionId(),
            List.of(new Variable("result", "ok")),
            "",
            StepExecutionStatus.COMPLETED
        ));
    };
}
```

`UpdateStepExecutionUseCase` is available as a Spring bean. Inject it wherever needed
to report task progress from asynchronous code.

---

## Process and step execution status

### Process status

| Status | Description |
|---|---|
| `PENDING` | Created, not yet started |
| `RUNNING` | At least one step is executing |
| `COMPLETED` | All steps finished successfully |
| `ERROR` | A step failed after exhausting retries |
| `CANCELLED` | Process was cancelled |

### Step execution status

| Status | Description |
|---|---|
| `CREATED` | Scheduled, waiting for the orchestration loop |
| `PENDING` | Task dispatched to worker, awaiting acknowledgement |
| `RUNNING` | Worker reported it started processing |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure |
| `TIMEOUT` | Step exceeded its configured timeout |
| `CANCELLED` | Step was cancelled (e.g. compensation) |

---

## Configuration reference

### Full reference

```properties
# --- Deployment mode ---
workflow.mode=kafka          # kafka | embedded (default: kafka)
workflow.persistence=jpa     # jpa | memory   (default: jpa)

# --- Database (workflow.persistence=jpa) ---
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update

# --- Kafka broker (workflow.mode=kafka) ---
spring.kafka.bootstrap-servers=localhost:9092

# --- Kafka topics ---
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeOutbox-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeUpstream-in-0.group=orchestrator-group
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group

# --- Spring Cloud Stream function bindings ---
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

### Minimum config — fully embedded

```properties
workflow.mode=embedded
workflow.persistence=memory

spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

Place workflow definitions as JSON files under `src/main/resources/workflows/`.

---

## Module: workflow-engine

### Maven dependency

```xml
<dependency>
    <groupId>io.mateu.workflow</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Key Spring beans (public API)

| Bean | Description |
|---|---|
| `ProcessUpstreamEventUseCase` | Entry point — start processes and handle integration events |
| `UpdateStepExecutionUseCase` | Report task progress from workers |
| `ProcessRepository` | Read/query process state |
| `StepExecutionRepository` | Read/query step execution state |
| `WorkflowDefinitionRepository` | Manage workflow definitions |

---

## Module: ia-agent-service

AI agent powered by Claude (Anthropic) that lets operators interact with the
orchestration engine in natural language via MCP tools.

See [`demo/ia-agent-service/README.md`](demo/ia-agent-service/README.md) for full documentation.

---

## Local development quickstart

### With Docker Compose (full mode)

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
```

```shell
docker-compose up -d
mvn spring-boot:run
```

### Without any external dependency (fully embedded)

```properties
# src/main/resources/application.properties
workflow.mode=embedded
workflow.persistence=memory
spring.autoconfigure.exclude=\
  org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration,\
  org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
```

```shell
mvn spring-boot:run
```
