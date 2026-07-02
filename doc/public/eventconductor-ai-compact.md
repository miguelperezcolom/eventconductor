# EventConductor — AI Reference (Compact)

EventConductor is an event-driven **workflow / saga orchestration engine** for Java/Spring. You describe a business process as a **workflow definition** (JSON or YAML) made of **steps**; the engine drives the state machine and delegates business logic to **workers**. It scales from a single in-process JVM (no Kafka, no DB) up to a multi-pod Kubernetes cluster (Kafka + PostgreSQL) with **no change to business code**.

**Maven dependency:**
```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>LATEST</version> <!-- see Maven Central badge -->
</dependency>
<!-- add io.mateu.workflow:forms-engine only if you use USER_TASK / human forms -->
```

---

## Deployment modes (two independent properties)

| Property | Values | Default |
|---|---|---|
| `workflow.mode` | `embedded` \| `kafka` | `embedded` |
| `workflow.persistence` | `memory` \| `jpa` | `memory` |

- **`embedded` + `memory`** (default): everything in-process, no external deps. Ideal for tests, local dev, embedding. State lost on restart.
- **`embedded` + `jpa`**: in-process dispatch, state in a DB (PostgreSQL/MariaDB/Oracle/H2). Survives restarts.
- **`kafka` + `jpa`**: full distributed. Events flow through Kafka topics; multiple orchestrators coordinate via PostgreSQL advisory locks.

Kafka/JPA autoconfiguration is excluded automatically in embedded/memory mode — no manual `spring.autoconfigure.exclude` needed.

---

## The model: a workflow is a list of steps

```json
{
  "id": "order-processing",
  "name": "Order Processing",
  "version": 1,
  "status": "ACTIVE",
  "steps": [
    { "id": "validate", "type": "ACTION", "name": "Validate", "topic": "order-validator" },
    { "id": "charge",   "type": "ACTION", "name": "Charge",   "topic": "payment-service",
      "preconditionStepId": "validate", "timeout": "PT30S", "retries": 2 },
    { "id": "end", "type": "END", "name": "Done", "preconditionStepId": "charge" }
  ]
}
```

In `embedded`+`memory` mode, definitions are loaded from `classpath:/workflows/` at startup. In `jpa` mode they can also be imported from Git.

### Core rules

- Ordering is **by data flow**, not array order: a step runs when its `preconditionStepId` step has completed.
- Every workflow has **exactly one `END`** step. With parallel branches, put a `JOIN` before the `END`.
- `preconditionExpression` is a **JEXL** expression evaluated against process variables; if `false`, the step is `SKIPPED`.
- Variables are `(name, value)` string pairs. Worker outputs are **merged** into process variables and visible to later steps and JEXL expressions.
- Add `"$schema"` (JSON) or a `# yaml-language-server:` comment (YAML) pointing at `workflow-definition-schema.json` for editor autocomplete.

### Step types

| Type | Purpose | Key field |
|---|---|---|
| `ACTION` | Dispatch a task to a worker | `topic` (Kafka mode) |
| `USER_TASK` | Pause for a human to submit a form | `formId` |
| `PROCESS` | Run a child workflow as a sub-process | `childWorkflowDefinitionId` |
| `FORK` | Start parallel branches | — (branch steps set `parallel: true`) |
| `JOIN` | Wait for all parallel branches | — |
| `END` | Complete the process | — |

### Step fields

`id`, `type`, `name`, `description`, `preconditionStepId`, `preconditionExpression` (JEXL), `parallel` (bool), `topic` (ACTION), `formId` (USER_TASK), `childWorkflowDefinitionId` (PROCESS), `timeout` (ISO-8601 `PT30S`/`PT1H30M` or ms int; `0`=none), `retries` (int), `rollbackable` (bool), `compensationStepId`.

---

## Start a process

Kafka: send `ProcessCreationRequested` to the `upstream` topic. Any mode: call the bean.

```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("order-processing", "order-123",
        List.of(new Variable("orderId", "123"), new Variable("amount", "99.90")))));
```

Cancel: `new ProcessCancellationRequested(processId)` through the same use case.
Query: `ProcessRepository.findById(id)` / `.findByBusinessKey("order-123")`.

---

## Implement a worker

A worker receives a `TaskExecutionRequested`, does work, and reports a status back. It is **stateless**; the engine handles retries/timeouts/errors.

**Embedded mode** — register one `EmbeddedTaskExecutor` bean; branch on `stepId`:
```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return request -> {
        // request.variables() includes outputs from previous steps
        update.handle(new UpdateStepExecutionCommand(
            request.taskExecutionId(),
            List.of(new Variable("result", "ok")),   // output vars, merged into process
            "",                                        // optional log
            StepExecutionStatus.COMPLETED));           // RUNNING | COMPLETED | ERROR
    };
}
```

**Kafka mode** — consume `TaskExecutionRequested` from `downstream`, publish `TaskStatusChanged` (status `COMPLETED` | `ERROR` | `RUNNING`) to `upstream`.

Report `RUNNING` for progress (resets the timeout clock). Long tasks can return now and call `UpdateStepExecutionUseCase` later (it's a bean available anywhere).

---

## Retries, timeouts & sagas

- `retries: N` — auto-retry on `ERROR` or `TIMEOUT` up to N times.
- `timeout` — after it elapses the step goes `TIMEOUT`, then retries if any remain, else `ERROR`.
- **Saga/compensation**: set `rollbackable: true` + `compensationStepId: "..."` on a step; if the process fails, the compensation step runs to undo it. Define compensation steps as normal `ACTION` steps (usually with no precondition).

---

## Statuses

- **Process**: `PENDING → RUNNING → COMPLETED | ERROR`, or `→ CANCELLED`. `ERROR` can be retried.
- **StepExecution**: `CREATED → PENDING → RUNNING → COMPLETED | ERROR`; `TIMEOUT → PENDING (retry) | ERROR`; `SKIPPED` when precondition is false; `CANCELLED`.
- Workers may only report `RUNNING`, `COMPLETED`, `ERROR`.

---

## Key beans (public API)

| Bean | Use |
|---|---|
| `ProcessUpstreamEventUseCase` | Start / cancel processes; entry point for integration events |
| `UpdateStepExecutionUseCase` | Report task progress from workers |
| `ProcessRepository` | Query process state (`findById`, `findByBusinessKey`, `findByStatus`) |
| `StepExecutionRepository` | Query step executions |
| `WorkflowDefinitionRepository` | Manage definitions (`findById`, `findByStatus`, `save`) |
| `EmbeddedTaskExecutor` | (You implement) worker bean in embedded mode |

`record Variable(String name, String value)`. Use `request.taskExecutionId()` (not stepId) when reporting via `UpdateStepExecutionCommand`.

---

## Gotchas

- **Order by `preconditionStepId`, not array position.** A step with no precondition starts immediately.
- **Exactly one `END`.** Parallel branches must funnel through a `JOIN` before `END`.
- **Report with `taskExecutionId`**, the value from `TaskExecutionRequested` — not the workflow `stepId`.
- **Variables are strings.** JEXL numeric comparisons operate on the string value (`amount > 1000`).
- In **embedded mode** the `topic` field is ignored (single executor); in **Kafka mode** `topic` is required for `ACTION`.
- Human steps (`USER_TASK`) need the `forms-engine` dependency and a form with matching `formId`.

Full reference: `eventconductor-ai-full.md`. Canonical docs: `doc/src/content/docs/`.
