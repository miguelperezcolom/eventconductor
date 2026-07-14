# EventConductor — AI Reference (Full)

EventConductor is a production-grade, event-driven **workflow / saga orchestration engine** for the Java/Spring ecosystem. You describe a business process as a **workflow definition** (JSON or YAML) composed of **steps**. The engine drives the state machine — scheduling steps, enforcing preconditions, retries, timeouts, and compensation — and delegates all business logic to **workers** (stateless microservices in Kafka mode, or in-process beans in embedded mode).

It scales from a single JVM with no external dependencies up to a multi-pod Kubernetes cluster (Kafka + PostgreSQL, coordinating via advisory locks and the outbox pattern) **without changing business code** — only two configuration properties.

---

## 1. Maven dependencies

```xml
<!-- workflow orchestration engine -->
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>LATEST</version>
</dependency>

<!-- only if you use USER_TASK steps (human forms) -->
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>forms-engine</artifactId>
  <version>LATEST</version>
</dependency>
```

Check the current version on the Maven Central badge in the README (artifacts under `io.mateu.workflow`).

Prebuilt standalone Docker images exist: `orchestrator-standalone-app`, `forms-standalone-app`, `worker-standalone-app`.

---

## 2. Deployment modes

Two independent properties control everything. No code changes to switch.

| Property | Values | Default | Meaning |
|---|---|---|---|
| `workflow.mode` | `embedded` \| `kafka` | `embedded` | How domain events are dispatched |
| `workflow.persistence` | `memory` \| `jpa` | `memory` | Where state is stored |

### Mode 1 — Fully embedded (`embedded` + `memory`)  ← default

No Kafka, no database. Everything runs in-process. Ideal for unit tests, local dev, and embedding in another app.

```properties
workflow.mode=embedded
workflow.persistence=memory
```

- Domain events dispatched synchronously on each repository `save()`.
- State held in `ConcurrentHashMap` — lost on restart.
- Definitions loaded from `classpath:/workflows/` at startup.
- Use `@WorkflowEmbeddedApplication` instead of `@SpringBootApplication` (excludes the web/UI/JPA layers from scanning). Kafka & JPA autoconfig are excluded automatically.

### Mode 2 — Semi-embedded (`embedded` + `jpa`)

In-process dispatch, state in a supported DB (PostgreSQL, MariaDB/MySQL, Oracle, or H2 for demos). Survives restarts.

```properties
workflow.mode=embedded
workflow.persistence=jpa
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```

- Events dispatched in-process via `EmbeddedOutboxRelay` (polls the outbox table).
- Definitions in `classpath:/workflows/` are imported to the DB at startup; can also be imported from Git.

### Mode 3 — Full distributed (`kafka` + `jpa`)

Requires Kafka + a supported DB. Designed for production multi-pod deployments.

```properties
workflow.mode=kafka
workflow.persistence=jpa
spring.kafka.bootstrap-servers=localhost:9092
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
# ... datasource + cloud stream bindings, see §10
```

- Domain events flow through Kafka topics (`outbox`, `upstream`, `downstream`).
- Multiple orchestrator instances coordinate via PostgreSQL advisory locks.
- Use a normal `@SpringBootApplication`.

The philosophy: **start fully embedded, grow into JPA and then Kafka** as scale demands.

---

## 3. Workflow definitions

Written in JSON or YAML (`.json`, `.yaml`, `.yml`); version-controlled and PR-reviewable.

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique workflow identifier |
| `name` | string | Human-readable name |
| `version` | integer | Version number |
| `description` | string | Optional |
| `status` | enum | `DRAFT` \| `ACTIVE` \| `DISABLED` \| `ARCHIVED` |
| `draftOfId` | string | ID of the production definition this is a working copy of; `null` otherwise |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (when limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when the limit is reached |
| `steps` | array | The step definitions |

### Definition statuses

| Status | Meaning |
|---|---|
| `DRAFT` | Under construction, not executable |
| `ACTIVE` | Accepts new process instances |
| `DISABLED` | No new instances; running ones continue |
| `ARCHIVED` | Retired |

### Editor autocomplete

JSON:
```json
{ "$schema": "https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json", "id": "my-workflow" }
```
YAML (first line):
```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/miguelperezcolom/eventconductor/main/modules/workflow-engine/src/main/resources/workflow-definition-schema.json
```

---

## 4. Step types

### ACTION — dispatch a task to a worker
```json
{ "id": "charge", "type": "ACTION", "name": "Charge Payment", "topic": "payment-service",
  "timeout": "PT30S", "retries": 3, "rollbackable": true, "compensationStepId": "refund" }
```
- **Kafka mode:** `topic` is the destination; a `TaskExecutionRequested` is published there. Required.
- **Embedded mode:** `topic` is ignored — all ACTION steps route to the single `EmbeddedTaskExecutor` bean. May be omitted.

### USER_TASK — pause for a human form
```json
{ "id": "approve", "type": "USER_TASK", "name": "Manager Approval",
  "formId": "expense-approval-form", "preconditionStepId": "submit", "timeout": 86400000 }
```
Creates a `FormExecution` in the forms engine. Requires `formId` and the `forms-engine` dependency.

### RULE — evaluate a business rule
```json
{ "id": "apply-discount", "type": "RULE", "name": "Apply the discount rule",
  "ruleId": "high-value-order", "preconditionStepId": "register-order" }
```
Dispatches `taskId=evaluate-rule` with a `ruleId` variable; any app embedding `rule-runtime` evaluates the rule (expression or decision table, JEXL) against the process variables and its outputs are merged back as process variables. Requires `ruleId` and a rule with that id in the rule catalog (`rule-engine`). The runtime fetches rules per `rules.source`: `local`, `classpath`, `rest` or `grpc` (+ optional Kafka cache refresh).

### PROCESS — run a child workflow
```json
{ "id": "run-kyc", "type": "PROCESS", "name": "Run KYC", "childWorkflowDefinitionId": "kyc-workflow" }
```
Parent variables pass to the child; child output variables merge back into the parent. Completes when the child completes.

### FORK / JOIN — parallel branches
```json
{ "id": "fork", "type": "FORK", "name": "Notify", "preconditionStepId": "process-order" },
{ "id": "email", "type": "ACTION", "topic": "email-service", "preconditionStepId": "fork", "parallel": true },
{ "id": "sms",   "type": "ACTION", "topic": "sms-service",   "preconditionStepId": "fork", "parallel": true },
{ "id": "join",  "type": "JOIN",  "name": "Sent", "preconditionStepId": "email" }
```
FORK starts branches whose steps set `parallel: true`; JOIN waits for all branches to finish.

### END — complete the process
```json
{ "id": "end", "type": "END", "name": "Done", "preconditionStepId": "last-step" }
```
Exactly one per workflow. Transitions the process to `COMPLETED`. With parallel branches, precede it with a JOIN.

### Common step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique within the workflow |
| `type` | enum | — | `ACTION`/`USER_TASK`/`RULE`/`PROCESS`/`FORK`/`JOIN`/`END` |
| `name` | string | — | Human-readable |
| `description` | string | — | Optional |
| `preconditionStepId` | string | — | Step that must complete first |
| `preconditionExpression` | string | — | JEXL; step is `SKIPPED` if it evaluates to `false` |
| `parallel` | boolean | `false` | Concurrent execution within a FORK branch |
| `topic` | string | — | Worker destination (ACTION, Kafka mode) |
| `formId` | string | — | Form to render (USER_TASK) |
| `ruleId` | string | — | Rule to evaluate (RULE) |
| `childWorkflowDefinitionId` | string | — | Child workflow (PROCESS) |
| `timeout` | duration | `0` | ISO-8601 (`PT30S`, `PT5M`, `PT1H30M`) or ms integer; `0` = none |
| `retries` | integer | `0` | Auto-retry attempts on ERROR/TIMEOUT |
| `rollbackable` | boolean | `false` | Enable saga compensation on failure |
| `compensationStepId` | string | — | Compensation step (needs `rollbackable: true`) |

---

## 5. Variables & JEXL

- Variables are `(name, value)` string pairs, passed at process creation and merged with each worker's output variables (same name overwrites).
- Available to every subsequent step and to JEXL `preconditionExpression`s.
- JEXL numeric comparisons run on the string representation: `"amount > 1000"`, `"approved == 'true'"`.

```json
{ "id": "review", "type": "USER_TASK", "formId": "review-form",
  "preconditionStepId": "check", "preconditionExpression": "amount > 1000" }
```

---

## 6. Starting, cancelling & querying a process

### Programmatically (any mode)
```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

// start
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("my-workflow", "order-123",
        List.of(new Variable("orderId", "123"), new Variable("amount", "99.90")))));

// cancel — running steps are cancelled, process → CANCELLED
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCancellationRequested(processId)));
```

### Via Kafka (mode: kafka)
Send to the `upstream` topic:
```json
{ "@type": "ProcessCreationRequested", "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [ { "name": "orderId", "value": "123" }, { "name": "amount", "value": "99.90" } ] }
```
`businessKey` is an optional human-readable identifier for later lookup.

### Query
```java
@Autowired ProcessRepository processRepository;
Process p  = processRepository.findById(processId);
Process p2 = processRepository.findByBusinessKey("order-123");
List<Process> running = processRepository.findByStatus(ProcessStatus.RUNNING);
```

---

## 7. Implementing workers

A worker receives a `TaskExecutionRequested`, performs business logic, and reports a `TaskStatusChanged` / `UpdateStepExecutionCommand` with the outcome and output variables. Workers are **stateless**; the orchestrator owns retries, timeouts, and error tracking.

```java
record TaskExecutionRequested(
    String taskExecutionId,       // <-- use THIS to report back
    String processId,
    String workflowDefinitionId,
    String stepId,
    String taskId,
    List<Variable> variables) {}  // process variables at this point (incl. prior outputs)
```

### Embedded worker (mode: embedded)

Register one `EmbeddedTaskExecutor` bean; branch on `stepId`. (You may also register per-topic beans named after the step `topic`.)

```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return request -> {
        switch (request.stepId()) {
            case "greet" -> {
                String name = varOr(request, "name", "World");
                update.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(new Variable("greeting", "Hello, " + name)),
                    "",                                   // optional log
                    StepExecutionStatus.COMPLETED));
            }
            default -> update.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(), List.of(),
                "Unknown step: " + request.stepId(),
                StepExecutionStatus.ERROR));
        }
    };
}
```

Per-topic bean form (bean name = step `topic`):
```java
@Bean("payment-service")
EmbeddedTaskExecutor payment(UpdateStepExecutionUseCase update) { return request -> { /* ... */ }; }
```

### Kafka worker (Spring Cloud Stream)

Consume `TaskExecutionRequested` from `downstream`; publish `TaskStatusChanged` to `upstream`.

```java
record TaskStatusChanged(String taskExecutionId, TaskStatus status,
                         List<Variable> variables, String log) {}

@Bean
Consumer<TaskExecutionRequested> myWorkerTopic(StreamBridge bridge) {
    return req -> {
        try {
            String result = doWork(req.variables());
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.COMPLETED,
                List.of(new Variable("result", result)), null));
        } catch (Exception e) {
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.ERROR, List.of(), e.getMessage()));
        }
    };
}
```

### Progress & async

- Report `RUNNING` for long tasks — resets the timeout clock.
- For async work, return immediately and call `UpdateStepExecutionUseCase` later (it's a Spring bean available anywhere).

### Output variables

Worker outputs are **merged into process variables** (overwriting same-named ones), and become available to all later steps and JEXL expressions.

---

## 8. Retries, timeouts & sagas (compensation)

- `retries: N` — retry a step up to N times on `ERROR` or `TIMEOUT`.
- `timeout` — on expiry the step goes `TIMEOUT`, then retries if attempts remain, else `ERROR`. A process in `ERROR` (after retries exhausted) can be retried, transitioning back to `RUNNING`.
- **Saga**: mark steps `rollbackable: true` with a `compensationStepId`. If the process fails, compensation steps run to undo completed work. Define compensation steps as ordinary `ACTION` steps (typically without a `preconditionStepId`).

```json
{
  "id": "booking-saga", "name": "Booking Saga", "version": 1, "status": "ACTIVE",
  "steps": [
    { "id": "reserve-hotel",  "type": "ACTION", "topic": "hotel-service",
      "rollbackable": true, "compensationStepId": "cancel-hotel", "retries": 2 },
    { "id": "reserve-flight", "type": "ACTION", "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "rollbackable": true, "compensationStepId": "cancel-flight" },
    { "id": "cancel-hotel",  "type": "ACTION", "topic": "hotel-service" },
    { "id": "cancel-flight", "type": "ACTION", "topic": "flight-service" },
    { "id": "end", "type": "END", "preconditionStepId": "reserve-flight" }
  ]
}
```

---

## 9. Statuses

### Process
`PENDING` (created) → `RUNNING` → `COMPLETED` | `ERROR`; `RUNNING`/`PENDING` → `CANCELLED`. `ERROR` is retriable.

### Step execution
| Status | Meaning |
|---|---|
| `CREATED` | Scheduled, waiting for the next orchestration tick |
| `PENDING` | Dispatched to worker, awaiting ack |
| `RUNNING` | Worker reported it started |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout with no retries left |
| `TIMEOUT` | Exceeded `timeout` — retries if attempts remain |
| `CANCELLED` | Process cancellation or saga compensation |
| `SKIPPED` | `preconditionExpression` was `false` |

Workers may only report `RUNNING`, `COMPLETED`, `ERROR`.

### Form execution
`Assigned` → `Completed`.

---

## 10. Kafka topics (mode: kafka)

| Topic | Direction | Payloads |
|---|---|---|
| `upstream` | into the orchestrator | `ProcessCreationRequested`, `ProcessCancellationRequested`, `TaskStatusChanged` |
| `downstream` | orchestrator → workers | `TaskExecutionRequested` (per ACTION step `topic`) |
| `outbox` | internal | orchestrator outbox relay |

Typical Spring Cloud Stream bindings:
```properties
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

---

## 11. Java API summary

### Entry points
- `ProcessUpstreamEventUseCase.handle(ProcessUpstreamEventCommand)` — wraps `ProcessCreationRequested` / `ProcessCancellationRequested`.
- `UpdateStepExecutionUseCase.handle(UpdateStepExecutionCommand)` — report worker progress.

```java
new UpdateStepExecutionCommand(
    taskExecutionId,                 // from TaskExecutionRequested
    List.of(new Variable("k","v")),  // output variables
    "log message",                   // optional
    StepExecutionStatus.COMPLETED);  // RUNNING | COMPLETED | ERROR
```

### Repositories
- `ProcessRepository`: `findById`, `findByBusinessKey`, `findAll`, `findByStatus`.
- `StepExecutionRepository`: `findByProcessId`, `findByStatus`.
- `WorkflowDefinitionRepository`: `findById`, `findByStatus`, `save`.

### Interfaces & records
```java
@FunctionalInterface interface EmbeddedTaskExecutor { void execute(TaskExecutionRequested request); }
record Variable(String name, String value) {}
```

### Domain model (selected fields)
- `Process`: `id`, `workflowDefinitionId`, `businessKey`, `status`, `variables`, `createdAt`, `updatedAt`.
- `StepExecution`: `id`, `processId`, `stepId`, `taskExecutionId`, `status`, `retryCount`, `startedAt`, `completedAt`, `log`.
- `WorkflowDefinition`: `id`, `name`, `version`, `status`, `steps`.

---

## 12. Git import & working copies (jpa mode)

**Git import** — clone repos at startup and import every valid definition file (has `name` + `steps`):
```yaml
workflow:
  git-import:
    webhook-secret: mysecret        # optional; enables HMAC-SHA256 verification of GitHub webhooks
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: main
        username: my-user           # optional (token auth)
        password: ghp_xxx           # optional
```
Also triggerable via the MCP tool `importWorkflowDefinitionsFromGit`, or a GitHub webhook to `POST /workflow/webhooks/github` (responds 202, imports in background).

**Working copies** — a `DRAFT` clone of a production definition (`draftOfId` = original). Edit safely, then **promote**: content is copied onto the original, `version`+1, working copy deleted, running processes unaffected. One working copy per definition.

---

## 13. Human tasks (forms-engine)

`USER_TASK` steps pause the process and create a `FormExecution` for a form identified by `formId`. Users submit via the UI or MCP tools; on submission the form's field values are merged into the process variables and the step completes. See `doc/src/content/docs/guides/form-definitions.md` and `user-tasks.md`.

---

## 14. AI / MCP integration

The engine exposes an MCP server so AI agents can operate it in natural language. Representative tools: `getProcessDetails`, `importWorkflowDefinitionsFromGit`, process listing/starting, and forms tools. See `doc/src/content/docs/guides/mcp-overview.md`.

---

## 15. Minimal fully-embedded example

`src/main/resources/application.properties`:
```properties
workflow.mode=embedded
workflow.persistence=memory
```
`src/main/resources/workflows/hello.json`:
```json
{ "id": "hello", "name": "Hello", "version": 1, "status": "ACTIVE",
  "steps": [
    { "id": "greet", "type": "ACTION", "name": "Greet" },
    { "id": "end",   "type": "END",    "name": "Done", "preconditionStepId": "greet" } ] }
```
Application:
```java
@WorkflowEmbeddedApplication
public class App {
    public static void main(String[] a) { SpringApplication.run(App.class, a); }

    @Bean
    EmbeddedTaskExecutor exec(UpdateStepExecutionUseCase update) {
        return req -> update.handle(new UpdateStepExecutionCommand(
            req.taskExecutionId(), List.of(new Variable("greeting", "Hello!")),
            "", StepExecutionStatus.COMPLETED));
    }
}
```

---

## Canonical sources of truth (this repo)

- `doc/public/eventconductor-ai-compact.md` — the compact version of this file.
- `doc/src/content/docs/guides/` — workflow-definitions, starting-a-process, workers, retries-timeouts-compensation, form-definitions, user-tasks, mcp-*.
- `doc/src/content/docs/reference/` — step-types, statuses, configuration, kafka-topics, java-api.
- `modules/workflow-engine/src/main/resources/workflow-definition-schema.json` — the JSON Schema for definitions.

Prefer these if anything here looks out of date.
