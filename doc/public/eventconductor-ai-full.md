# EventConductor — AI Reference (Full)

EventConductor is a production-grade, event-driven **workflow / saga orchestration engine** for the Java/Spring ecosystem. You describe a business process as a **workflow definition** (JSON or YAML) composed of **steps**. The engine drives the state machine — scheduling steps, enforcing preconditions, retries, timeouts, and compensation — and delegates all business logic to **workers** (stateless microservices in Kafka mode, or in-process beans in embedded mode).

It scales from a single JVM with no external dependencies up to a multi-pod Kubernetes cluster (Kafka + PostgreSQL, coordinating via Kafka partition ownership and the outbox pattern) **without changing business code** — only two configuration properties.

---

## 1. Maven dependencies

```xml
<!-- workflow orchestration engine -->
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>2.0.0</version>
</dependency>

<!-- only if you use USER_TASK steps (human forms) -->
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>forms-engine</artifactId>
  <version>2.0.0</version>
</dependency>
```

2.0.0 is the latest release at the time of writing — check the Maven Central badge in the README / `CHANGELOG.md` for the newest one (artifacts under `io.mateu.workflow`).

Prebuilt standalone Docker images exist: `orchestrator-standalone-app`, `forms-standalone-app`, `worker-standalone-app`, `rule-standalone-app`.

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
- Multiple orchestrator instances coordinate via Kafka partition ownership: events are keyed by process, so each process is owned by exactly one instance, fenced by an optimistic-locking version on the aggregates for the rebalance window. (Embedded mode, with no partitions, falls back to a per-process row lock — `SELECT … FOR UPDATE`.)
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
| `paused` | boolean | Runtime flag (not authored in the .ec) — pause/resume. While true all processes are held and new instances (cron included) are born-`PAUSED` |
| `disabled` | boolean | Runtime flag (not authored in the .ec) — disable/enable. While true, no new instances (cron included); running ones continue |
| `archived` | boolean | Runtime flag (not authored in the .ec) — set by the git-import prune to hide a removed definition |
| `limitConcurrentExecutions` | boolean | Cap concurrent running instances |
| `maxConcurrentExecutions` | integer | Max instances (when limit enabled) |
| `enqueueOnLimit` | boolean | Queue new instances when the limit is reached |
| `cronExpression` | string | Spring cron; the engine starts a new instance at each occurrence (deterministic business keys, multi-pod safe) |
| `defaultMaxStepExecutions` | integer | Default cap on executions per step (validated metadata; not enforced at runtime today) |
| `steps` | array | The step definitions |

### Runtime state

Definitions are authored as `.ec` files and imported — never edited in the UI. Two orthogonal runtime flags (outside the `.ec`): pause/resume (processes held; new instances born paused) and disable/enable (no new instances). A definition removed from its repo is archived by the import prune. Disabled and archived definitions start no new instances.

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

### Definition status

| Field | Values | Meaning |
|---|---|---|
| `status` | `ACTIVE` (default), `DISABLED`, `ARCHIVED` | Whether the workflow accepts new instances. Declaring it in the `.ec` is a **floor**: an operator can take a workflow out of service at runtime, but cannot put one into service that its own definition closes. `ARCHIVED` also hides it from the listing |
| `disabled`, `archived` | boolean | The older spelling, still read: `true` means the corresponding status |
| `paused` | boolean | A different axis: a paused workflow still accepts instances (born `PAUSED`); a disabled one accepts none. A workflow can be both |

## 4. Step types

Steps run as a **pure dataflow**: a step starts when all its preconditions have `COMPLETED`
and its JEXL guard holds, concurrently with every other eligible step — array order is
irrelevant and the `parallel` flag is deprecated and ignored. **Roots rule:** a step with no
preconditions does not run; it must be a `START`, a `WAIT_FOR_MESSAGE` beginning a flow, or
another step's `compensationStepId` (rejected at load otherwise).

### START — entry point
```json
{ "id": "start", "type": "START", "name": "Start" }
```
No worker: completes instantly at process creation, making its successors eligible. Must have
**no** preconditions. Several `START` steps = concurrent entry branches. To migrate an old
definition, add one `START` and point the old first steps at it.

### ACTION — dispatch a task to a worker
```json
{ "id": "charge", "type": "ACTION", "name": "Charge Payment", "topic": "payment-service",
  "preconditionStepId": "start",
  "timeout": "PT30S", "retries": 3, "compensable": true, "compensationStepId": "refund" }
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

### TIMER — durable wait
```json
{ "id": "wait-3-days", "type": "TIMER", "name": "Wait 3 days",
  "duration": "PT72H", "preconditionStepId": "send-reminder" }
```
Pauses the process without a worker: the step stays `PENDING` and the scheduler completes it once the due moment passes; the wait survives restarts. `duration` is ISO-8601 or ms; `untilVariable` names a process variable holding an ISO-8601 date/date-time and takes precedence. A misconfigured timer ends the step `ERROR` through the normal failure pipeline.

### WAIT_FOR_MESSAGE — wait for a message
```json
{ "id": "await-payment", "type": "WAIT_FOR_MESSAGE", "name": "Await payment confirmation",
  "messageName": "payment-confirmed", "correlationExpression": "businessKey",
  "preconditionStepId": "charge", "timeout": "PT24H" }
```
Waits until a `MessageReceived(messageName, correlationKey, variables)` arrives (REST / Kafka `upstream` as `"type":"message-received"` / MCP `sendMessage` / a `SEND_MESSAGE` step). **Both `messageName` and `correlationExpression` are required**: the correlation key is the value of the JEXL `correlationExpression` evaluated against process variables (fail-closed — an unevaluable expression matches nothing); write `"correlationExpression": "businessKey"` to correlate by business key. Message variables merge into the process. Unmatched messages are ignored, not buffered. `timeout`/`retries` keep their usual meaning. REST delivery: `POST /workflow/api/messages` with `{"messageName", "correlationKey", "variables": {..}}` responds 202; `X-Api-Key` header required when `workflow.message-api.api-key` is set.

Previously named `MESSAGE`: the old name is a deserialization alias (persisted in-flight state and old definition files keep loading, and legacy steps without a `correlationExpression` fall back to the business key), but new/reimported definitions must use `WAIT_FOR_MESSAGE` with an explicit `correlationExpression`.

### SEND_MESSAGE — emit a message (fire-and-forget)
```json
{ "id": "notify-payment", "type": "SEND_MESSAGE", "name": "Notify payment",
  "messageName": "payment-confirmed", "correlationExpression": "orderId",
  "messageVariables": ["paymentId", "amount"], "preconditionStepId": "charge" }
```
The throw side: no worker involved. On start the engine evaluates `correlationExpression` (same JEXL context as preconditions), emits `MessageReceived(messageName, correlationKey, variables)` through the outbox and completes the step immediately — in-engine process-to-process signaling without an ACTION step + worker. **Both `messageName` and `correlationExpression` are required.** `messageVariables` lists the process-variable names the message carries; empty/absent = none (process state is never sent implicitly). Fire-and-forget: delivery is not acknowledged, and a message matching no waiting process is discarded (not buffered). Failure is **loud**: missing `messageName`/`correlationExpression` or a correlation key that cannot be evaluated puts the step in `ERROR` (normal retry/compensation pipeline) — deliberately NOT the silent fail-closed of precondition guards.

### PROCESS — run a child workflow
```json
{ "id": "run-kyc", "type": "PROCESS", "name": "Run KYC",
  "childWorkflowDefinitionId": "kyc-workflow", "outputVariables": ["kycResult"],
  "preconditionStepId": "create-customer", "timeout": "PT1H" }
```
No worker: when the step starts, the engine creates a child process of
`childWorkflowDefinitionId` (which must differ from the workflow's own id — direct
self-recursion is rejected) carrying **all** parent variables, with the deterministic business
key `parent:<stepExecutionId>` (idempotent — redelivered creation events are deduped). The
parent step waits `PENDING`. On child `COMPLETED`, the parent step completes and copies back
**only** the child variables named in `outputVariables` (empty/absent = none). On child
`ERROR` or `CANCELLED`, the parent step goes `ERROR` (normal retry/compensation pipeline).
`timeout` bounds the wait. Cancellation propagates down: a parent step ending `CANCELLED`,
`ERROR` or `TIMEOUT` (retries exhausted) cancels a still-running child, cascading to
grandchildren; while retries remain the child keeps running (a retried step re-attaches to it
via the deterministic business key).

### FORK / JOIN — parallel branches
```json
{ "id": "fork", "type": "FORK", "name": "Notify", "preconditionStepId": "process-order" },
{ "id": "email", "type": "ACTION", "topic": "email-service", "preconditionStepId": "fork" },
{ "id": "sms",   "type": "ACTION", "topic": "sms-service",   "preconditionStepId": "fork" },
{ "id": "join",  "type": "JOIN",  "name": "Sent", "preconditionStepIds": ["email", "sms"] }
```
Both are no-worker nodes that complete instantly. FORK is the explicit fan-out: every step
preconditioned on it starts concurrently (any step type fans out the same way — FORK just
makes it visible). JOIN is the barrier/converge point: its **multiple preconditions**
(`preconditionStepIds`) must ALL complete before it runs. Do not use `parallel: true` — it is
deprecated and ignored.

### END — complete the process
```json
{ "id": "end", "type": "END", "name": "Done", "preconditionStepId": "last-step" }
```
Exactly one per workflow. Transitions the process to `COMPLETED`. With parallel branches, precede it with a JOIN.

### Common step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Unique within the workflow |
| `type` | enum | — | `START`/`ACTION`/`USER_TASK`/`RULE`/`TIMER`/`WAIT_FOR_MESSAGE`/`SEND_MESSAGE`/`PROCESS`/`FORK`/`JOIN`/`END` |
| `name` | string | — | Human-readable |
| `description` | string | — | Optional |
| `preconditionStepId` | string | — | Single step that must complete first |
| `preconditionStepIds` | string[] | — | Steps that must ALL complete first; wins over the singular form when non-empty |
| `preconditions` | object[] | — | `{stepId, expression?}` per incoming link: the condition belongs to that route in, not to the step. Wins over both spellings above. A link whose `expression` is falsy is **not satisfied**, so the step waits (it is not skipped, and the process does not finish around it) |
| `preconditionExpression` | string | — | JEXL guard on the step, whatever route reached it; while falsy the step is never run (stays `CREATED`, → `CANCELLED` when `END` fires) |
| `parallel` | boolean | `false` | **Deprecated and ignored** (kept for deserialization of old files) |
| `topic` | string | — | Worker destination (ACTION, Kafka mode) |
| `formId` | string | — | Form to render (USER_TASK) |
| `ruleId` | string | — | Rule to evaluate (RULE) |
| `childWorkflowDefinitionId` | string | — | Child workflow (PROCESS; must differ from the workflow's own id) |
| `outputVariables` | string[] | — | Child variables copied back to the parent on completion (PROCESS); empty/absent = none |
| `duration` | duration | `0` | Wait length (TIMER); ISO-8601 or ms |
| `untilVariable` | string | — | Variable holding an ISO-8601 date/date-time (TIMER); wins over `duration` |
| `messageName` | string | — | Message to wait for / emit (WAIT_FOR_MESSAGE / SEND_MESSAGE; **required** for both) |
| `correlationExpression` | string | — | JEXL producing the correlation key (WAIT_FOR_MESSAGE / SEND_MESSAGE; **required** for both — use `businessKey` for the business key) |
| `messageVariables` | string[] | — | Process-variable names the outgoing message carries (SEND_MESSAGE); empty/absent = none |
| `timeout` | duration | `0` | ISO-8601 (`PT30S`, `PT5M`, `PT1H30M`) or ms integer; `0` = none |
| `retries` | integer | `0` | Auto-retry attempts on ERROR/TIMEOUT |
| `compensable` | boolean | `false` | Enable saga compensation on failure |
| `compensationStepId` | string | — | Compensation step (needs `compensable: true`) |
| `maxSuccessfulExecutions` | integer | `0` | Cap on successful executions of this step (validated metadata; not enforced at runtime today) |

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

// cancel — running/pending steps → CANCELLED, process → CANCELLED
@Autowired CancelProcessUseCase cancelProcessUseCase;
cancelProcessUseCase.handle(new CancelProcessCommand(processId));

// retry a process in ERROR — transitions back to RUNNING
@Autowired RetryProcessUseCase retryProcessUseCase;
retryProcessUseCase.handle(new RetryProcessCommand(processId));

// pause a PENDING/RUNNING process → PAUSED; resume → back to RUNNING
@Autowired PauseProcessUseCase pauseProcessUseCase;
@Autowired ResumeProcessUseCase resumeProcessUseCase;
pauseProcessUseCase.handle(new PauseProcessCommand(processId));
resumeProcessUseCase.handle(new ResumeProcessCommand(processId));

// pause/resume a whole definition by id (bulk + born-paused new instances)
@Autowired PauseWorkflowUseCase pauseWorkflowUseCase;
@Autowired ResumeWorkflowUseCase resumeWorkflowUseCase;
pauseWorkflowUseCase.handle(workflowDefinitionId);
resumeWorkflowUseCase.handle(workflowDefinitionId);
```

Pause semantics: pause holds the frontier, not in-flight work — running workers finish and
their reports are accepted (steps complete, variables merge), messages still complete
`WAIT_FOR_MESSAGE` steps, but successors do not start until resume. Timer/timeout clocks
freeze (the schedulers skip paused processes; on resume every non-terminal started step's
`startedAt` is shifted forward by the pause duration) and blocking-error handling is
deferred. Pausing a definition sets its runtime `paused` flag and pauses all its
PENDING/RUNNING processes; new instances — cron included — are still created, **born
PAUSED**, and start on resume.

### Via Kafka (mode: kafka)
Send to the `upstream` topic:
```json
{ "type": "process-creation-requested", "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [ { "name": "orderId", "value": "123" }, { "name": "amount", "value": "99.90" } ] }
```
`businessKey` is an optional human-readable identifier for later lookup.

### Query
```java
@Autowired ProcessRepository processRepository;
Optional<Process> p  = processRepository.findById(processId);
Optional<Process> p2 = processRepository.findByBusinessKey("order-123");
long running = processRepository.countByStatus(ProcessStatus.RUNNING);
```
There is no `findByStatus`; filter `findAll()` (or use `find(...)`) for status-based queries.

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

Mind the two `Variable` records: `UpdateStepExecutionCommand` takes `io.mateu.workflow.domain.aggregates.Variable`, while the events (`TaskExecutionRequested`, `TaskStatusChanged`, `ProcessCreationRequested`) use `io.mateu.workflow.dtos.Variable` — import the right one per use.

The bean is called **on the dispatching thread**, which under `workflow.persistence=jpa` is the single outbox relay — the only thread advancing every process in the JVM. A worker that blocks there stops all of them, and the symptom is that processes created afterwards show every step in `CREATED` ("waiting for its preconditions"). Give outbound calls timeouts (a `RestClient` from `builder.baseUrl(url).build()` has none), and set `workflow.embedded.worker-threads` above zero to dispatch through a pool — after giving ACTION steps a `timeout`, which is what recovers a task lost to a crash once delivery no longer means completion. An exception escaping the bean fails the step; reporting `ERROR` yourself is still better, since a throw carries no output variables.

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
// io.mateu.workflow.dtos.events.integration
record TaskStatusChanged(String taskExecutionId, TaskStatus status,
                         List<Variable> variables) {}

@Bean
Consumer<TaskExecutionRequested> myWorkerTopic(StreamBridge bridge) {
    return req -> {
        try {
            String result = doWork(req.variables());
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.COMPLETED,
                List.of(new Variable("result", result))));
        } catch (Exception e) {
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.ERROR, List.of()));
        }
    };
}
```

There is no log component on `TaskStatusChanged`; task logs are emitted through the separate `TaskLogEmitted(String taskExecutionId, MessageType messageType, String message)` event.

### Progress & async

- Report `RUNNING` for long tasks — resets the timeout clock.
- For async work, return immediately and call `UpdateStepExecutionUseCase` later (it's a Spring bean available anywhere).

### Output variables

Worker outputs are **merged into process variables** (overwriting same-named ones), and become available to all later steps and JEXL expressions.

---

## 8. Retries, timeouts & sagas (compensation)

- `retries: N` — retry a step up to N times on `ERROR` or `TIMEOUT`.
- `timeout` — on expiry the step goes `TIMEOUT`, then retries if attempts remain, else `ERROR`. A process in `ERROR` (after retries exhausted) can be retried, transitioning back to `RUNNING`.
- **Saga**: mark steps `compensable: true` with a `compensationStepId`. When any step fails after exhausting retries, the engine runs the compensations of **every executed compensable step** (completed steps plus the one that just failed) **sequentially, in reverse execution order** — latest-executed undone first, each starting only once the previous compensation completes. When the whole chain finishes the process ends in the terminal **`COMPENSATED`** state; if a compensation itself fails after its own retries, the chain halts and the process stays `ERROR`. Define compensation steps as ordinary `ACTION` steps with **no preconditions** — being named as a `compensationStepId` is what makes them reachable, and the dataflow never starts a step that has nothing to wait for. (Older definitions anchor them with `"preconditionExpression": "false"`; still supported. An anchor without the guard is a live branch of the happy path.)

```json
{
  "id": "booking-saga", "name": "Booking Saga", "version": 1,
  "steps": [
    { "id": "start", "type": "START", "name": "Start" },
    { "id": "reserve-hotel",  "type": "ACTION", "topic": "hotel-service",
      "preconditionStepId": "start",
      "compensable": true, "compensationStepId": "cancel-hotel", "retries": 2 },
    { "id": "reserve-flight", "type": "ACTION", "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "compensable": true, "compensationStepId": "cancel-flight" },
    { "id": "cancel-hotel",  "type": "ACTION", "topic": "hotel-service" },
    { "id": "cancel-flight", "type": "ACTION", "topic": "flight-service" },
    { "id": "end", "type": "END", "preconditionStepId": "reserve-flight" }
  ]
}
```

---

## 9. Statuses

### Process
`PENDING` (created) → `RUNNING` → `COMPLETED` | `ERROR`; `RUNNING`/`PENDING` → `CANCELLED`. `ERROR` is retriable. A failed process that fully rolls back via saga compensation ends in the terminal `COMPENSATED` state (`ERROR` → `COMPENSATED`); both `ERROR` and `COMPENSATED` are sticky failure states, distinguished by whether the side effects were undone (see §8). `PENDING`/`RUNNING` → `PAUSED` → `RUNNING` on resume (`PAUSED` → `CANCELLED` also works); see §6 for pause semantics.

### Step execution
| Status | Meaning |
|---|---|
| `CREATED` | Scheduled, waiting for the next orchestration tick |
| `PENDING` | Dispatched to worker, awaiting ack |
| `RUNNING` | Worker reported it started |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout with no retries left |
| `TIMEOUT` | Exceeded `timeout` — retries if attempts remain |
| `CANCELLED` | Process cancellation, saga compensation, or step never run when `END` fired |

There is **no `SKIPPED` status**. A step whose `preconditionExpression` is falsy is simply never run: it stays `CREATED` and is flipped to `CANCELLED` when the `END` step fires. Because dependent steps require their `preconditionStepId` step to be `COMPLETED`, a never-run step permanently blocks its dependents — give conditional chains an alternative path to `END`.

Workers may only report `RUNNING`, `COMPLETED`, `ERROR`.

### Form execution
`PENDING` → `ASSIGNED` → `COMPLETED`; `CANCELLED`.

---

## 10. Kafka topics (mode: kafka)

| Topic | Direction | Payloads |
|---|---|---|
| `upstream` | into the orchestrator | `ProcessCreationRequested`, `TaskStatusChanged`, `MessageReceived` (`{"type":"message-received","messageName":"...","correlationKey":"...","variables":[{"name":"...","value":"..."}]}`) |
| `downstream` | orchestrator → workers | `TaskExecutionRequested` (per ACTION step `topic`) |
| `outbox` | internal | orchestrator outbox relay |

Spring Cloud Stream bindings. The engine contributes its own as lowest-precedence defaults —
destinations, a consumer group per binding, and `consumer.batch-mode=true`, which its batch
consumers require (without it the payload arrives as a `byte[]` and every event dies with
`ClassCastException: class [B cannot be cast to class java.util.List`). The function list is
yours, since only the application knows what it composes:
```properties
spring.cloud.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group=worker-group
spring.cloud.stream.kafka.binder.auto-create-topics=true
```

---

## 11. Java API summary

### Entry points
- `ProcessUpstreamEventUseCase.handle(ProcessUpstreamEventCommand)` — wraps `ProcessCreationRequested` and other upstream events.
- `CancelProcessUseCase.handle(CancelProcessCommand)` / `RetryProcessUseCase.handle(RetryProcessCommand)` — cancel / retry a process by id.
- `PauseProcessUseCase.handle(PauseProcessCommand)` / `ResumeProcessUseCase.handle(ResumeProcessCommand)` — pause / resume a process by id (see §6).
- `PauseWorkflowUseCase.handle(String)` / `ResumeWorkflowUseCase.handle(String)` — pause / resume a whole definition by id (bulk + born-paused new instances).
- `UpdateStepExecutionUseCase.handle(UpdateStepExecutionCommand)` — report worker progress.

```java
new UpdateStepExecutionCommand(
    taskExecutionId,                 // from TaskExecutionRequested
    List.of(new Variable("k","v")),  // output variables
    "log message",                   // optional
    StepExecutionStatus.COMPLETED);  // RUNNING | COMPLETED | ERROR
```

### Repositories

All extend `CrudStore<T>` (`findById` → `Optional<T>`, `save`, `findAll`, `deleteAllById`, `find`). There is no `findByStatus` anywhere.
- `ProcessRepository`: + `findByBusinessKey(String)` → `Optional<Process>`, `countByStatus(ProcessStatus)`.
- `StepExecutionRepository`: + `findByProcess(Process)`, `findPendingOrRunning()`.
- `WorkflowDefinitionRepository`: bare `CrudStore<WorkflowDefinition>`.

### Analytics
- `ProcessAnalyticsService.analyze(definitionIdOrName, TimeWindow)` / `analyzeAll(TimeWindow)` — per-definition analytics computed on demand in any mode: instance counts by status, completion/error/cancellation rates, throughput per day, avg/p95 process duration, avg/p95 per-step duration with the slowest step flagged as bottleneck. `TimeWindow.lastDays(30)` / `TimeWindow.all()`.

### Observability
Micrometer metrics for the workflow, forms and rule engines — exposed at `GET /actuator/prometheus` when a Prometheus `MeterRegistry` is on the classpath (`micrometer-registry-prometheus` + `management.endpoints.web.exposure.include=health,prometheus`) — plus OTLP tracing configured via `TRACING_SAMPLING` (`management.tracing.sampling.probability`) and `OTLP_TRACING_ENDPOINT` (`management.otlp.tracing.endpoint`). Full reference: `doc/src/content/docs/reference/observability.md`.

### Interfaces & records
```java
@FunctionalInterface interface EmbeddedTaskExecutor { void execute(TaskExecutionRequested request); }
record Variable(String name, String value) {}
```

### Domain model (selected fields)
- `Process`: `id`, `name`, `workflowDefinitionId`, `workflowDefinitionVersion`, `workflowDefinitionJson`, `businessKey`, `variables`, `status`, `completionPercentage`, `created`, `started`, `finished`, `pausedAt` (set while `PAUSED`; used to shift step clocks on resume), `parentStepExecutionId` (set on child processes started by a parent `PROCESS` step; `null` otherwise).
- `StepExecution`: `id`, `processId`, `workflowDefinitionId`, `stepId`, `stepJson`, `variables`, `status`, `workerId`, `startedAt`, `finishedAt`, `attemptCount`. The step-execution `id` **is** the `taskExecutionId` used in events; there is no separate field, no `retryCount`/`completedAt`/`log`.
- `WorkflowDefinition`: `id`, `name`, `version`, `description`, `paused`, `disabled`, `archived` (runtime flags), `limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit`, `cronExpression`, `defaultMaxStepExecutions`, `steps`.

---

## 12. Git import & working copies (jpa mode)

**Git import** — clone repos at startup and import every valid definition file (has `name` + `steps`):
```yaml
workflow:
  git-import:
    webhook-secret: mysecret        # optional; verifies inbound webhooks (HMAC or token, per provider)
    repositories:
      - url: https://github.com/your-org/workflow-defs.git
        branch: main
        username: my-user           # optional (token auth)
        password: ghp_xxx           # optional
```
Also triggerable via the MCP tool `importWorkflowDefinitionsFromGit`, or a git webhook to `POST /workflow/webhooks/{provider}` (`github`/`gitlab`/`bitbucket`/`generic`; responds 202, imports in background). The webhook reloads **only the repository and branch named in the push** (payload parsed; unmatched pushes are acknowledged and ignored; an unparseable payload reloads everything). Definitions removed from a repo are **pruned** — workflow definitions are archived, forms/rules deleted (git-imported only; classpath/hand-authored never touched; tracked per instance, resets on restart). Verification per provider using `webhook-secret`: GitHub/Bitbucket HMAC-SHA256 (`X-Hub-Signature-256`/`X-Hub-Signature`), GitLab token (`X-Gitlab-Token`), generic token (`X-Webhook-Token`); blank secret disables it. Forms and rules expose the same at `/forms/webhooks/{provider}` and `/rules/webhooks/{provider}`.


---

## 13. Human tasks (forms-engine)

`USER_TASK` steps pause the process and create a `FormExecution` for a form identified by `formId`. Users submit via the UI or MCP tools; on submission the form's field values are merged into the process variables and the step completes. See `doc/src/content/docs/guides/form-definitions.md` and `user-tasks.md`.

---

## 14. AI / MCP integration

The engine exposes an MCP server so AI agents can operate it in natural language. Workflow tools: `listProcesses`, `getProcessDetails`, `findProcessByBusinessKey`, `getProcessLogs`, `retryProcess`, `pauseProcess` / `resumeProcess` (pause/resume a process — see §6 semantics), `pauseWorkflow` / `resumeWorkflow` (pause/resume a whole definition), `sendMessage` (resume WAIT_FOR_MESSAGE steps), `getWorkflowAnalytics` / `findBottleneck` (per-definition analytics and bottleneck detection), `importWorkflowDefinitionsFromGit`. There is no start-process tool. Plus forms tools and rule catalog tools (`listRules`, `evaluateRule`, ...). See `doc/src/content/docs/guides/mcp-overview.md`.

---

## 14b. Observability

- **Metrics** — Micrometer; Prometheus scraping by default, and OTLP push when
  `management.otlp.metrics.export.enabled=true`.
- **Tracing** — OpenTelemetry over OTLP, off until `management.tracing.sampling.probability > 0`.
  The outbox row carries the producing trace's W3C `traceparent`, and the relay publishes as a
  continuation of it, so one process is **one trace** rather than one per hop. The engine names
  `eventconductor.step-over`, `eventconductor.dispatch-step` and `eventconductor.correlate-message`.
- Both go through ports with no-op defaults (`WorkflowMetrics`, `WorkflowTracing`), so the engine
  libraries run with no observability dependencies at all.

## 15. Minimal fully-embedded example

`src/main/resources/application.properties`:
```properties
workflow.mode=embedded
workflow.persistence=memory
```
`src/main/resources/workflows/hello.json`:
```json
{ "id": "hello", "name": "Hello", "version": 1,
  "steps": [
    { "id": "start", "type": "START",  "name": "Start" },
    { "id": "greet", "type": "ACTION", "name": "Greet", "preconditionStepId": "start" },
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
