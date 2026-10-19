# Implementing workers

A worker does the work of an `ACTION` step and reports back: complete (with output variables),
fail (retryable or final), or progress. Workers are stateless and must be **idempotent** (delivery
is at-least-once). The engine owns retries, timeouts and compensation. Canonical:
`doc/src/content/docs/guides/workers.md`, `guides/http-workers.md`, `reference/task-contracts.md`.

| Where the worker runs | How |
|---|---|
| in the engine's JVM, simplest | one `EmbeddedTaskExecutor` bean (low-level) |
| in the engine's JVM, typed, alongside built-in HTTP_CALL/LLM_CALL | `worker-embedded` + `TaskRegistration`/generated `TaskHandler` beans |
| another JVM service over Kafka | `worker-kafka` + the same `TaskHandler`s, or a raw Spring Cloud Stream consumer + `WorkerReply` |
| any language over HTTP | `/workflow/api/workers` long-poll (Python SDK in `sdks/python`) |

## 1. Embedded, low level: `EmbeddedTaskExecutor`

ONE bean receives every task (the step `topic` is ignored in embedded mode — there are no per-topic
beans); branch on `stepId()` (or `taskId()`).

```java
import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;   // NOT io.mateu.workflow.dtos.Variable

@Configuration
class Workers {
    @Bean
    EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
        return req -> {
            switch (req.stepId()) {
                case "charge" -> update.handle(new UpdateStepExecutionCommand(
                        req.taskExecutionId(),                     // report with taskExecutionId, never stepId
                        List.of(new Variable("chargeId", "ch-1")),  // merged into the process
                        "", StepExecutionStatus.COMPLETED));
                default -> update.handle(new UpdateStepExecutionCommand(
                        req.taskExecutionId(), List.of(), "Unknown step " + req.stepId(),
                        StepExecutionStatus.ERROR, true));          // true = non-retryable
            }
        };
    }
}
```

- `TaskExecutionRequested(taskExecutionId, processId, workflowDefinitionId, stepId, taskId, variables,
  attempt, priority)` — `variables` holds all process variables (`io.mateu.workflow.dtos.Variable`).
  Record patterns over it must list all 8 components (2.33 added `priority`).
- Statuses a worker reports: `RUNNING` (progress; resets the timeout clock), `COMPLETED`, `ERROR`.
- Reason prefix `[non-retryable] ` in the log, or `nonRetryable = true`, skips the step's retries.
- The bean runs on the dispatching thread (with `jpa`, the single outbox relay): a blocking worker
  stalls every process in the JVM. Use timeouts on outbound calls and `workflow.embedded.worker-threads`
  to dispatch through a pool. A throw fails the step, but reporting `ERROR` yourself is better.
- A hand-written `EmbeddedTaskExecutor` replaces the SDK's executor — then built-in `HTTP_CALL` /
  `LLM_CALL` / `AGENT_CALL` tasks also land in your bean. If you use those steps embedded, use option 2.
- Async: return at once and call `UpdateStepExecutionUseCase` later (any bean can).

## 2. Typed handlers: `TaskHandler` (worker-embedded or worker-kafka)

The recommended way: declare a task contract (`src/main/resources/tasks/charge.ectask`), let the
`generate-worker-sources` goal generate `ChargeV1Input/Output` and a `ChargeV1Task` interface, and
implement it as a `@Component`. The same handler runs embedded (`worker-embedded`) or over Kafka
(`worker-kafka`).

```yaml
id: charge
version: 1
group: payments
topic: payments
input:
  bookingId: { type: string, required: true }
  amount: { type: number, required: true }
output:
  chargeId: { type: string }
errors:
  - code: CARD_DECLINED
    description: the card was declined
```
A step uses it with `task: charge` (pinned to the latest version at import) or `task: charge@1`.

Without codegen, register a handler by hand (an `ACTION` with no `task:` is matched by its step id):

```java
record ChargeIn(String bookingId, java.math.BigDecimal amount) {}
record ChargeOut(String chargeId) {}

@Bean
TaskRegistration<ChargeIn, ChargeOut> charge() {         // io.mateu.workflow.worker.api.TaskRegistration
    return new TaskRegistration<>("charge", 1, "payments", ChargeIn.class, ChargeOut.class,
            (in, ctx) -> new ChargeOut("ch-" + in.bookingId()));
}
```
Return to complete; throw `TaskFailure` (code, reason, retryable) for a business failure; any other
exception fails the step and is retried per `retries`. `TaskContext` gives ids, `isCancelled()`, `progress(...)`.

## 3. Kafka, raw (Spring Cloud Stream)

Consume `TaskExecutionRequested` from `downstream` (or the step's `topic`) and reply through
`WorkerReply` (`io.mateu.workflow.worker`), which retries and throws rather than dropping a reply:

```java
@Bean
Consumer<TaskExecutionRequested> payments(StreamBridge bridge) {
    return req -> {
        try {
            WorkerReply.completed(bridge, req, List.of(new Variable("chargeId", "ch-1")));
        } catch (Exception e) {
            WorkerReply.failed(bridge, req, List.of(), e.toString());   // the reason reaches the process log
        }
    };
}
```
Never `bridge.send("upstream", …)` and ignore its `false`. Task consumers redeliver with back-off when
a reply cannot be published (`eventconductor.worker.consumer.retry.*`; on main after 2.33.0, #565).

## 4. Any language over HTTP

Enable on the engine (any mode):
```properties
workflow.workers.http.enabled=true
workflow.workers.http.topics=payments
workflow.workers.http.api-keys.python-payments=${PAYMENTS_WORKER_KEY}
```
Tasks for those topics are queued (`http_task` table with jpa) and leased. Endpoints under
`/workflow/api/workers` (header `X-Api-Key`): `POST /activate {workerId, topics, maxTasks, leaseMs, waitMs}`
(long poll) → `{tasks: [{id, stepId, variables, attempt, leaseToken, …}]}`; `POST /tasks/{id}/complete
{attempt, variables}`; `POST /tasks/{id}/fail {attempt, code, message, retryable}`; `POST /tasks/{id}/heartbeat
{leaseToken, extendMs}` → `{cancelled, deadline}`. An expired lease redelivers; a stale attempt gets 409.

Python SDK (`pip install ./sdks/python/eventconductor-worker`, stdlib only, Python ≥ 3.9, not on PyPI):
```python
from eventconductor_worker import Worker, TaskError

worker = Worker("http://localhost:8080", api_key="my-worker-key", concurrency=4)

@worker.task("payments")
def charge(task):
    if task.variables["amount"] == "0":
        raise TaskError("ZERO_AMOUNT", "nothing to charge", retryable=False)
    return {"chargeId": f"ch-{task.process_id}"}

worker.run()
```

## Output variables

Reported outputs are merged into the process variables (same name overwrites) and are visible to later
steps, guards and templates — except `forEach` iterations, whose outputs are collected into `output`.
