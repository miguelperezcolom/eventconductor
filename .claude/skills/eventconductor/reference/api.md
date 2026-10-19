# Java API (in-process)

Spring beans of the engine; inject them anywhere. For HTTP/MCP see [operations.md](operations.md).

## Start, cancel, retry, pause

```java
import io.mateu.workflow.dtos.Variable;                                  // events use dtos.Variable
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("order-processing", "order-123",
        List.of(new Variable("amount", "99.90")))));
```

Package `io.mateu.workflow.application.usecases.process.*` — each `handle(command)`:

| Use case | Command | Effect |
|---|---|---|
| `cancel.CancelProcessUseCase` | `CancelProcessCommand(processId)` | steps and process → `CANCELLED`; children cascade |
| `retry.RetryProcessUseCase` | `RetryProcessCommand(processId)` | rerun the failed steps of an `ERROR`/`CANCELLED` process |
| `restart.RestartProcessUseCase` | `RestartProcessCommand(processId)` | rerun from the top with the original variables |
| `pause.PauseProcessUseCase` / `resume.ResumeProcessUseCase` | `PauseProcessCommand` / `ResumeProcessCommand` | hold the frontier; in-flight work still lands; clocks freeze |

`io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase.handle(definitionId)` /
`ResumeWorkflowUseCase` pause a whole definition (new instances are born `PAUSED`; use `DISABLED` to refuse them).

Live-process operations (`application.usecases.process.operate`): `SetProcessVariablesUseCase`,
`CompleteStepManuallyUseCase`, `RetryFromStepUseCase`, `MigrateProcessUseCase` — each command carries a
`reason` and `requestedBy`. Higher level: `io.mateu.workflow.application.services.ProcessOperationsService`
(what REST/MCP use; `start(...)` honours `startPolicy`/`businessKeyPolicy`).

Synchronous invocation in code: `SyncInvocationService.start(...)` / `.await(...)`.
Inbound domain events (embedded): `InboundEventService.receive(...)` or publish a Spring `InboundEvent`.

## Reporting worker results

```java
updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
    taskExecutionId,                                   // from TaskExecutionRequested
    List.of(new io.mateu.workflow.domain.aggregates.Variable("result", "ok")),
    "optional log line",
    StepExecutionStatus.COMPLETED));                   // RUNNING | COMPLETED | ERROR
// 5th arg `nonRetryable` (boolean): fail without spending retries
```

Two `Variable` records exist: `io.mateu.workflow.dtos.Variable` (events: `TaskExecutionRequested`,
`TaskStatusChanged`, `ProcessCreationRequested`) and `io.mateu.workflow.domain.aggregates.Variable`
(`UpdateStepExecutionCommand`). Mixing them does not compile.

## Reading state

`ProcessRepository.findById(id)` / `findByBusinessKey(key)` → `Optional<Process>` (the run holding the
key now), `countByStatus(status)`; `StepExecutionRepository.findByProcess(process)`,
`findPendingOrRunning()`; `WorkflowDefinitionRepository` (CrudStore). No `findByStatus`. For listings
use `ProcessListingService` / REST `GET /workflow/api/processes`. Analytics: `ProcessAnalyticsService`.

Selected fields — `Process`: `id`, `workflowDefinitionId`, `workflowDefinitionVersion`, `businessKey`,
`variables`, `status`, `created/started/finished`, `parentStepExecutionId`. `StepExecution`: `id` (= the
`taskExecutionId`), `stepId`, `status`, `attemptCount`, `startedAt`, `finishedAt`.

## Statuses

- **Process**: `PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `ERROR`, `CANCELLED`, `COMPENSATED`
  (saga rolled back cleanly), `COMPENSATION_FAILED` (rollback halted — alert on it). `ERROR` and
  `CANCELLED` can be retried/restarted.
- **Step**: `CREATED → PENDING → RUNNING → COMPLETED | ERROR | TIMEOUT`, plus `AWAITING_RETRY`
  (backoff before the next attempt), `WAITING_ON_LOCK`, `CANCELLED`. No `SKIPPED`: a branch not taken
  ends `CANCELLED` when the process completes.

## Kafka topics (mode: kafka)

`upstream` (in: `ProcessCreationRequested`, `TaskStatusChanged`, `MessageReceived` as
`{"type":"message-received","messageName":"…","correlationKey":"…","variables":[{"name":"k","value":"v"}]}`,
operations commands), `downstream` (out: `TaskExecutionRequested`, cancellations; or the step's
`topic`), `outbox` (internal), `http-calls`, `llm-calls`, `dead-letter`, `human-tasks`. Canonical:
`doc/src/content/docs/reference/kafka-topics.md`.
