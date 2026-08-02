# EventConductor — AI Reference (Compact)

EventConductor is an event-driven **workflow / saga orchestration engine** for Java/Spring. You describe a business process as a **workflow definition** (JSON or YAML) made of **steps**; the engine drives the state machine and delegates business logic to **workers**. It scales from a single in-process JVM (no Kafka, no DB) up to a multi-pod Kubernetes cluster (Kafka + PostgreSQL) with **no change to business code**.

**Maven dependency:**
```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>1.0-beta.014</version> <!-- check Maven Central / CHANGELOG.md for the newest release -->
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
  "steps": [
    { "id": "start",    "type": "START",  "name": "Start" },
    { "id": "validate", "type": "ACTION", "name": "Validate", "topic": "order-validator",
      "preconditionStepId": "start" },
    { "id": "charge",   "type": "ACTION", "name": "Charge",   "topic": "payment-service",
      "preconditionStepId": "validate", "timeout": "PT30S", "retries": 2 },
    { "id": "end", "type": "END", "name": "Done", "preconditionStepId": "charge" }
  ]
}
```

In `embedded`+`memory` mode, definitions are loaded from `classpath:/workflows/` at startup. In `jpa` mode they can also be imported from Git.

### Core rules

- Ordering is **pure data flow**, not array order: a step runs when **all** its preconditions (`preconditionStepIds` array, or the singular `preconditionStepId`) have completed and its guard holds. All eligible steps start **concurrently** — an active step never blocks unrelated branches. The `parallel` flag is deprecated and **ignored** (still deserializes).
- **Roots rule:** every step with no preconditions must be a `START` or a `WAIT_FOR_MESSAGE` — every flow must enter through one; violating definitions are rejected at load. Migration for old definitions: add one `START` step and point the old first steps at it.
- Declare one `END` step (recommended; the engine also completes implicitly when no runnable steps remain). With parallel branches, put a `JOIN` (with `preconditionStepIds` listing all branches) before the `END`.
- `preconditionExpression` is a **JEXL** expression evaluated against process variables; while falsy the step is simply never run (stays `CREATED`) and is flipped to `CANCELLED` when the `END` step fires. **Trap:** dependents wait for `COMPLETED`, so a step whose guard never turns true permanently blocks every step whose `preconditionStepId` points at it — give such chains an alternative path to `END`.
- Variables are `(name, value)` string pairs. Worker outputs are **merged** into process variables and visible to later steps and JEXL expressions.
- Add `"$schema"` (JSON) or a `# yaml-language-server:` comment (YAML) pointing at `workflow-definition-schema.json` for editor autocomplete.

### Step types

| Type | Purpose | Key field |
|---|---|---|
| `START` | Entry point: no worker, completes instantly at process creation; must have **no** preconditions; several STARTs = concurrent entry branches | — |
| `ACTION` | Dispatch a task to a worker | `topic` (Kafka mode) |
| `USER_TASK` | Pause for a human to submit a form | `formId` |
| `RULE` | Evaluate a business rule; outputs become process variables | `ruleId` |
| `TIMER` | Durable wait for a duration or until a date from a variable | `duration` / `untilVariable` |
| `WAIT_FOR_MESSAGE` | Durable wait for a message (correlated by required JEXL `correlationExpression`; use `businessKey` for the business key); deliver via `POST /workflow/api/messages`, Kafka `upstream` (`"type":"message-received"`), MCP `sendMessage`, or a `SEND_MESSAGE` step. Previously named `MESSAGE` (legacy alias still deserializes) | `messageName` + `correlationExpression` (both required) |
| `SEND_MESSAGE` | Emit a `MessageReceived` (fire-and-forget, no worker) and complete immediately; carries only the variables listed in `messageVariables` (absent = none). Missing fields or an unevaluable correlation key → step `ERROR` (fails loud, unlike fail-closed precondition guards); a message matching no waiting process is discarded, not buffered | `messageName` + `correlationExpression` (both required) |
| `PROCESS` | Run a child workflow: starts a child process (business key `parent:<stepExecutionId>`, idempotent) with ALL parent variables; parent step waits `PENDING`; on child `COMPLETED` copies back only the child variables named in `outputVariables` (absent = none); child `ERROR`/`CANCELLED` → parent step `ERROR`. `timeout` bounds the wait. Parent step CANCELLED/ERROR/TIMEOUT (retries exhausted) cancels a still-running child (cascades) | `childWorkflowDefinitionId` (≠ own workflow id) |
| `FORK` | Explicit fan-out: no worker, completes instantly — all steps preconditioned on it start concurrently | — |
| `JOIN` | Barrier: waits until ALL steps in its `preconditionStepIds` complete, then completes instantly | `preconditionStepIds` |
| `END` | Complete the process | — |

### Step fields

`id`, `type`, `name`, `description`, `preconditionStepId` (single), `preconditionStepIds` (string array — ALL must complete; wins over the singular when non-empty), `preconditionExpression` (JEXL), `parallel` (bool, **deprecated/ignored**), `topic` (ACTION), `formId` (USER_TASK), `ruleId` (RULE), `childWorkflowDefinitionId` (PROCESS), `outputVariables` (PROCESS: string array of child variables copied back to the parent; absent = none), `duration` (TIMER: ISO-8601 or ms), `untilVariable` (TIMER: variable holding an ISO-8601 date/date-time; wins over `duration`), `messageName` + `correlationExpression` (WAIT_FOR_MESSAGE / SEND_MESSAGE, both **required**), `messageVariables` (SEND_MESSAGE: string array of process-variable names the outgoing message carries; empty/absent = none), `timeout` (ISO-8601 `PT30S`/`PT1H30M` or ms int; `0`=none), `retries` (int), `rollbackable` (bool), `compensationStepId`, `maxSuccessfulExecutions` (int).

A workflow definition can also declare a `cronExpression` (Spring syntax) to start a new process instance at each occurrence, with deterministic business keys so multiple pods never duplicate an occurrence, and a `defaultMaxStepExecutions` (int). Note: `defaultMaxStepExecutions` / `maxSuccessfulExecutions` are today validated metadata, not enforced at runtime.

### Built-in analytics

`ProcessAnalyticsService` (Spring bean, any mode) computes per definition and time window: instance counts by status, completion/error/cancellation rates, throughput per day, avg/p95 process duration and avg/p95 per-step duration with the slowest step flagged as the bottleneck. Also exposed as an **Analytics** UI page and the `getWorkflowAnalytics` / `findBottleneck` MCP tools.

**Observability:** Micrometer metrics for the workflow/forms/rule engines (`/actuator/prometheus` with a registry on the classpath) and OTLP tracing (`TRACING_SAMPLING`, `OTLP_TRACING_ENDPOINT`) — see `doc/src/content/docs/reference/observability.md`.

---

## Start a process

Kafka: send `ProcessCreationRequested` to the `upstream` topic. Any mode: call the bean.

```java
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("order-processing", "order-123",
        List.of(new Variable("orderId", "123"), new Variable("amount", "99.90")))));
```

Cancel: `cancelProcessUseCase.handle(new CancelProcessCommand(processId))` — running/pending steps go `CANCELLED`, process → `CANCELLED`.
Retry a process in `ERROR`: `retryProcessUseCase.handle(new RetryProcessCommand(processId))`.
Pause/resume: `pauseProcessUseCase.handle(new PauseProcessCommand(processId))` (PENDING/RUNNING → `PAUSED`) / `resumeProcessUseCase.handle(new ResumeProcessCommand(processId))`. Pause holds the frontier, not in-flight work: workers finish and their reports are accepted, messages still complete WAIT_FOR_MESSAGE steps, but successors don't start and timer/timeout clocks freeze (on resume `startedAt` is shifted forward by the pause duration). `PauseWorkflowUseCase.handle(defId)` / `ResumeWorkflowUseCase.handle(defId)` pause a whole definition (runtime `paused` flag, orthogonal to its lifecycle status): all its PENDING/RUNNING processes are paused and new instances — cron included — are still created, **born PAUSED**.
Query: `ProcessRepository.findById(id)` / `.findByBusinessKey("order-123")` — both return `Optional<Process>`.

---

## Implement a worker

A worker receives a `TaskExecutionRequested`, does work, and reports a status back. It is **stateless**; the engine handles retries/timeouts/errors.

**Embedded mode** — register one `EmbeddedTaskExecutor` bean; branch on `stepId`. Note there are two `Variable` records: `UpdateStepExecutionCommand` takes `io.mateu.workflow.domain.aggregates.Variable`; the events (`TaskExecutionRequested`, `TaskStatusChanged`, `ProcessCreationRequested`) use `io.mateu.workflow.dtos.Variable`.
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
- **Saga/compensation**: set `rollbackable: true` + `compensationStepId: "..."` on a step. When any step fails after exhausting retries, the compensations of **all executed rollbackable steps** run **sequentially in reverse execution order** (saga rollback); when the chain finishes the process ends in the terminal **`COMPENSATED`** state (a failed compensation halts the chain and leaves it `ERROR`). Define compensation steps as normal `ACTION` steps anchored to the step they compensate with `"preconditionExpression": "false"` — the dataflow never starts them (and the roots rule is satisfied); the compensation pipeline starts them directly, ignoring the guard.

---

## Statuses

- **Process**: `PENDING → RUNNING → COMPLETED | ERROR`, or `→ CANCELLED`. `ERROR` can be retried. `PENDING`/`RUNNING → PAUSED → RUNNING` (pause/resume; `PAUSED → CANCELLED` also works).
- **StepExecution**: `CREATED → PENDING → RUNNING → COMPLETED | ERROR`; `TIMEOUT → PENDING (retry) | ERROR`; `CANCELLED` on process cancellation or when `END` fires with the step never run (e.g. falsy `preconditionExpression`). There is no `SKIPPED` status.
- Workers may only report `RUNNING`, `COMPLETED`, `ERROR`.

---

## Key beans (public API)

| Bean | Use |
|---|---|
| `ProcessUpstreamEventUseCase` | Start processes; entry point for integration events |
| `CancelProcessUseCase` / `RetryProcessUseCase` | Cancel / retry a process (`CancelProcessCommand` / `RetryProcessCommand`) |
| `PauseProcessUseCase` / `ResumeProcessUseCase` | Pause / resume a process (`PauseProcessCommand` / `ResumeProcessCommand`) |
| `PauseWorkflowUseCase` / `ResumeWorkflowUseCase` | Pause / resume a whole definition by id (`handle(String)`): bulk pause/resume + born-paused new instances |
| `UpdateStepExecutionUseCase` | Report task progress from workers |
| `ProcessRepository` | Query process state (`findById`/`findByBusinessKey` → `Optional<Process>`, `findAll`, `countByStatus`) |
| `StepExecutionRepository` | Query step executions (`findByProcess(Process)`, `findPendingOrRunning()`) |
| `WorkflowDefinitionRepository` | Manage definitions (plain CrudStore: `findById` → `Optional`, `findAll`, `save`, `deleteAllById`) |
| `EmbeddedTaskExecutor` | (You implement) worker bean in embedded mode |

`record Variable(String name, String value)`. Use `request.taskExecutionId()` (not stepId) when reporting via `UpdateStepExecutionCommand`.

---

## Gotchas

- **Order by preconditions, not array position.** All eligible steps start concurrently; `parallel` is deprecated and ignored.
- **Roots rule.** A step with no preconditions must be a `START` or a `WAIT_FOR_MESSAGE`, or the definition is rejected at load. Old definitions migrate by adding one `START` and pointing the old first steps at it.
- **Exactly one `END`.** Parallel branches must funnel through a `JOIN` (its `preconditionStepIds` = the branch ends) before `END`.
- **Report with `taskExecutionId`**, the value from `TaskExecutionRequested` — not the workflow `stepId`.
- **Variables are strings.** JEXL numeric comparisons operate on the string value (`amount > 1000`).
- In **embedded mode** the `topic` field is ignored (single executor); in **Kafka mode** `topic` is required for `ACTION`.
- Human steps (`USER_TASK`) need the `forms-engine` dependency and a form with matching `formId`.

Full reference: `eventconductor-ai-full.md`. Canonical docs: `doc/src/content/docs/`.
