# Plan: action steps as built-in workers, shipped inside the orchestrator

> Status: **DECISIONS RESOLVED (2026-10-24)**, implementation not started. One PR per phase;
> each phase compiles and tests green on its own.

## 1. Goal

The orchestration core decides the flow (what runs next, guards, CHOICE/FORK/JOIN, timers, waits,
retries, compensation, incidents). It does **not** perform actions. Every step that *does* something
in the outside world (`HTTP_CALL`, `LLM_CALL`, `PUBLISH_EVENT`, `AGENT`) becomes a task that a worker
serves, with exactly the same contract as any external worker: `TaskExecutionRequested` out,
`UpdateStepExecution` back.

The point of the built-in steps was to **not have to write or deploy a worker for a trivial task**.
So the built-in workers ship **in the same executable as the orchestrator**: one deployable, two parts
(the orchestrator and a worker with the built-in tasks). They can still be split off into their own
deployment when someone wants egress or secrets isolated.

Decided in conversation (2026-10-24):

| # | Decision |
|---|---|
| A | The core keeps no action logic: action steps are tasks served by built-in workers. |
| B | Built-in workers run in the orchestrator's executable by default; separate deployments become opt-in. |
| C | **Kafka mode: the orchestrator consumes its own built-in task topics** (`worker-kafka`), rather than an in-process shortcut. Durability, sharing across replicas, retries, timeouts and cancellation stay on the one path every worker uses. |
| D | **`PUBLISH_EVENT` becomes a task too.** The outbox stays: the step-over writes the `TaskExecutionRequested` to it atomically with the step's state; the worker sends the business event and reports back. |
| E | The DSL does not change: same step types, same fields. Only where they run changes. |

## 2. Where things stand (main @ 395bdeb2)

| Step | How the core dispatches it | Who executes it | Same executable today? |
|---|---|---|---|
| `HTTP_CALL` | `TaskExecutionRequested`, taskId `http-call@1`, topic `http-calls` | `worker-http` (`HttpCallHandler`, `SecretResolver`, `HostGuard`) | Embedded: yes, if on the classpath (via `worker-embedded`). Kafka: **no**, `apps/http-worker-standalone-app` + `charts/.../http-worker.yaml` |
| `LLM_CALL` | same, topic `llm-calls` | `worker-llm` | Same as above, `llm-worker-standalone-app` |
| `AGENT` | taskId `run-agent@1`, topic `agent-calls` | external agent runtime (not in this repo) | no |
| `PUBLISH_EVENT` | **in the core**: `StepOverProcessUseCase.resolvePublishSteps` renders it, `stepExecution.publish(event)` puts it in the outbox, `OutboxRelay` → `ExternalEventSender` (Kafka) / `ExternalEventPublisher` (embedded) | core | yes, but coupled |

Per-type knowledge still spread through the core:

- `StepExecution.java:456-463`: default topic per type.
- `StepExecution.java:842-905`: per-type rendering (`HttpRequestRenderer`, `LlmRequestRenderer`,
  `AgentRequestRenderer`) and dispatch.
- `StartStepExecutionUseCase.java:116-133`: a second if-chain recomputing the taskId.
- `HttpTaskRouting`: hard-coded `BUILT_IN_TASK_IDS` / `BUILT_IN_TOPICS`.
- `UpdateStepExecutionSink`: special case for AGENT's invocation id.

What already works for us:

- `UpdateStepExecutionUseCase:112-170` already handles a worker answering a step with `awaitReply`
  (RUNNING/COMPLETED leave it waiting for the reply). This is what `HTTP_CALL` uses, and `PUBLISH_EVENT` will use it too.
- `StepExecution.armReplyWait` arms the reply wait **at dispatch**, in the same unit of work, so a
  reply can never overtake its subscription. Unchanged.
- `ExternalEventRenderer` already produces a stable `eventId` (per attempt when awaiting a reply).
- `WorkerKafkaBindingDefaults` merges `consumeWorkerEvent` into `spring.cloud.function.definition`,
  so it composes with the orchestrator's `consumeOutbox;consumeUpstream`.

## 3. Design

### 3.1 One registry of built-in tasks in the core

`domain/services/BuiltInTasks`: for each action step type, its `taskId`, default topic, and the
renderer that turns (step, process, scope) into task variables:

| StepType | taskId | default topic | request variable |
|---|---|---|---|
| `HTTP_CALL` | `http-call@1` | `http-calls` | `__http` |
| `LLM_CALL` | `llm-call@1` | `llm-calls` | `__llm` |
| `AGENT` | `run-agent@1` | `agent-calls` | `__agent` |
| `PUBLISH_EVENT` | `publish-event@1` | `event-publications` | `__event` (the rendered `ExternalEventRequested`) |

`StepExecution.start()`, `StepExecution.topic()`, `StartStepExecutionUseCase` and `HttpTaskRouting`
read from it instead of branching. Rendering stays in the core, because it needs the process's
template context, and that keeps the event id deterministic across redeliveries. Secrets and the
actual I/O stay in the worker, which is already how it works for HTTP and LLM.

### 3.2 `PUBLISH_EVENT` as a task

- **Core.** `resolvePublishSteps` still renders and validates the event (destination known, payload
  size, template errors still fail the step), including the per-attempt `eventId` when awaiting a
  reply. But instead of `stepExecution.publish(event)` + COMPLETED, it dispatches
  `TaskExecutionRequested(publish-event@1, __event=…)` and leaves the step PENDING. Since it is in the
  step-over's transaction, it rides the outbox atomically with the step's state.
- **Worker.** New module `worker-events` with `PublishEventHandler`, which sends the event to its destination:
  - Kafka: to the destination's topic, keyed, synchronously, as a CloudEvent with `ce_id = eventId`
    (moves `ExternalEventSender` + the destination→topic map out of the engine).
  - Embedded: through `ExternalEventPublisher` in-process, loopback included.
  - Returns COMPLETED, or RUNNING/COMPLETED with `awaitReply`, which `UpdateStepExecutionUseCase` already handles.
- **Guarantees.** The end-to-end guarantee is at-least-once, as today (today the relay can also send
  then crash before marking). A redelivered task re-sends the same `eventId`, so consumers dedupe on
  `ce_id`. Ordering of sequential PUBLISH_EVENTs is preserved, and is actually stronger, because the
  next step only dispatches after the previous one was sent.
- **Cost.** One extra hop of latency. In exchange, PUBLISH_EVENT gets uniform retries, timeouts,
  incidents and metrics (`eventconductor.step.duration` now measures the real send).
- **In-flight processes.** The old path completes the step in the same transaction as the publish,
  so no PENDING PUBLISH_EVENT exists from the old code. No migration is needed.

### 3.3 Built-in workers inside the orchestrator

- **Kafka mode.** `orchestrator-standalone-app` depends on `worker-kafka` + `worker-http` +
  `worker-llm` + `worker-events`. A new `BuiltInWorkersBindingDefaults` sets
  `consumeWorkerEvent-in-0.destination` to the enabled built-in topics (comma-separated:
  `http-calls,llm-calls,event-publications`), with group `eventconductor-builtin-workers`. Replies go to
  `upstream` like any worker. Scaling the orchestrator scales the built-in workers.
- **Switches.** `workflow.builtin-workers.{http,llm,events}.enabled` (default true). Turning one off
  removes its topic from the binding, so a separate deployment, the existing standalone apps, takes
  it over. `HttpTaskRouting`'s rule "built-in topics are not routed over HTTP unless named" stays.
- **Embedded mode.** The core module `workflow-engine` must **not** depend on the workers (that is the
  whole point). A pom-only `eventconductor-builtin-workers` aggregator brings `worker-embedded` +
  `worker-http` + `worker-llm` + `worker-events`. The scaffold skill and the docs add it by default, and
  `dev-app` uses it.
- **Strict mode.** With several topics on one consumer, the dispatcher only gets built-in taskIds.
  Unknown ones keep failing as today under `workflow.worker.strict`.
- **Security trade-off, documented.** By default the orchestrator now holds the HTTP secrets and LLM
  keys, and needs egress. The Helm network policy gains an opt-in egress rule for the orchestrator.
  Whoever wants isolation turns the built-in worker off and deploys the standalone one.

### 3.4 AGENT

The runtime lives outside this repo. This plan only moves AGENT onto the registry (topic, taskId,
invocation-id echo as a declared property of the task rather than a special case). Bringing an
in-repo `worker-agent` into the orchestrator is a separate decision (§6).

### 3.5 Out of scope, but the same shape

`RULE` (`rule-runtime`, `evaluate-rule`) and `USER_TASK` (`forms-engine`, `complete-form`) are built-in
tasks too. They go into the registry in phase 1 so the core's if-chains disappear entirely, but their
packaging does not change here.

## 4. Phases

1. **Registry refactor, no behaviour change.** `BuiltInTasks`; `StepExecution.start/topic`,
   `StartStepExecutionUseCase`, `HttpTaskRouting`, `UpdateStepExecutionSink` read from it. All existing
   tests green unchanged.
2. **`worker-events` + PUBLISH_EVENT as a task.** New module, handler, core dispatch change, removal of
   `ExternalEventSender` / outbox-publish path from the engine. Tests: workflow-test kit (embedded),
   awaitReply round trip, redelivery reuses the eventId, destination unknown still fails at render,
   loopback in embedded mode.
3. **Built-in workers in the orchestrator (Kafka).** Dependencies + `BuiltInWorkersBindingDefaults` +
   switches; `workflow-dist-e2e`: the orchestrator **alone**, with no http/llm worker containers,
   runs HTTP_CALL, LLM_CALL (stub model) and PUBLISH_EVENT end to end; and with
   `builtin-workers.http.enabled=false` + standalone http-worker, that one serves it.
4. **Embedded aggregator.** `eventconductor-builtin-workers` pom; `dev-app`, testbench, examples and the
   scaffold skill use it.
5. **Deployment.** Helm: orchestrator gets the `http`/`llm` config (allowedHosts, connections, secrets
   via `existingSecret`), `httpWorker`/`llmWorker` stay `enabled: false` and, when enabled, switch the
   orchestrator's built-in one off. Compose: drop the `http-worker` service from the default stack.
6. **Docs + CHANGELOG.** Architecture page ("orchestrator = core + built-in workers"), PUBLISH_EVENT
   guarantees (at-least-once, dedupe on `ce_id`), the isolation recipe, the eventconductor skills.

## 5. Compatibility

- Definitions: unchanged.
- Kafka deployments that already run `http-worker` / `llm-worker`: two consumer groups on the same
  topic would each run every task. The upgrade note says: either disable the built-in one, or drop the
  standalone one. Phase 5's Helm wiring does the first automatically.
- New topic `event-publications` (auto-created where `auto-create-topics` is on; listed in the topic
  provisioning docs).
- PUBLISH_EVENT behaviour change (completes after the send instead of in the same transaction): minor
  version with a CHANGELOG entry under *Changed*.

## 6. Open questions

- Topic name for PUBLISH_EVENT tasks: `event-publications` (proposed) vs `publish-events`.
- AGENT: bring an agent runtime into the repo as `worker-agent` and ship it in the orchestrator, or
  keep it external.
- Whether `rule-runtime` should also move into the orchestrator executable (it is the same argument).
