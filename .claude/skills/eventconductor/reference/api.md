# Java API

Inject these Spring beans anywhere in the application context.

## Entry points

```java
// Start a process (any deployment mode)
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("workflow-id", "business-key",
        List.of(new Variable("k", "v")))));

// Cancel / retry a process by id
@Autowired CancelProcessUseCase cancelProcess;
@Autowired RetryProcessUseCase retryProcess;

cancelProcess.handle(new CancelProcessCommand(processId));  // running/pending steps → CANCELLED, process → CANCELLED
retryProcess.handle(new RetryProcessCommand(processId));    // ERROR process → back to RUNNING

// Report worker progress
@Autowired UpdateStepExecutionUseCase updateStepExecution;

updateStepExecution.handle(new UpdateStepExecutionCommand(
    taskExecutionId,                    // from TaskExecutionRequested
    List.of(new Variable("result","ok")),
    "optional log",
    StepExecutionStatus.COMPLETED));    // RUNNING | COMPLETED | ERROR
```

## Repositories

All extend `CrudStore<T>`: `findById` → `Optional<T>`, `save`, `findAll`, `deleteAllById`, `find`.
There is **no `findByStatus`** on any of them.
```java
ProcessRepository:            findByBusinessKey(String) → Optional<Process>, countByStatus(ProcessStatus)
StepExecutionRepository:      findByProcess(Process), findPendingOrRunning()
WorkflowDefinitionRepository: (bare CrudStore)

Process p = processRepository.findByBusinessKey("order-123").orElseThrow();
```

## Records & interfaces

```java
record Variable(String name, String value) {}

@FunctionalInterface
interface EmbeddedTaskExecutor { void execute(TaskExecutionRequested request); }
```

Two `Variable` records with that shape exist — import per use:
`io.mateu.workflow.dtos.Variable` for events (`TaskExecutionRequested`, `TaskStatusChanged`,
`ProcessCreationRequested`); `io.mateu.workflow.domain.aggregates.Variable` for
`UpdateStepExecutionCommand`.

## Domain model (selected fields)

- `Process`: `id`, `name`, `workflowDefinitionId`, `workflowDefinitionVersion`, `workflowDefinitionJson`, `businessKey`, `variables`, `status`, `completionPercentage`, `created`, `started`, `finished`.
- `StepExecution`: `id`, `processId`, `workflowDefinitionId`, `stepId`, `stepJson`, `variables`, `status`, `workerId`, `startedAt`, `finishedAt`, `attemptCount`. The step-execution `id` **is** the `taskExecutionId` (no separate field; no `retryCount`/`completedAt`/`log`).
- `WorkflowDefinition`: `id`, `name`, `version`, `description`, `status`, `draftOfId`, `limitConcurrentExecutions`, `maxConcurrentExecutions`, `enqueueOnLimit`, `cronExpression`, `defaultMaxStepExecutions`, `steps`.

## Statuses

- **Process**: `PENDING → RUNNING → COMPLETED | ERROR`; `→ CANCELLED`. `ERROR` is retriable.
- **StepExecution**: `CREATED → PENDING → RUNNING → COMPLETED | ERROR`; `TIMEOUT → CREATED (retry) | ERROR`; `CANCELLED`. No `SKIPPED`: a step with a falsy precondition guard stays `CREATED` and is cancelled when `END` fires.

## Kafka (mode: kafka)

- `upstream` — inbound: `ProcessCreationRequested`, `TaskStatusChanged`, `MessageReceived`
  (`{"type": "message-received", "messageName": "...", "correlationKey": "...", "variables": [{"name": "k", "value": "v"}]}`).
- `downstream` — outbound to workers: `TaskExecutionRequested`.
- `outbox` — internal relay.

## REST

- `POST /workflow/api/messages` — deliver a message to waiting WAIT_FOR_MESSAGE steps. Body
  `{"messageName": "...", "correlationKey": "...", "variables": {"k": "v"}}`; responds 202.
  `X-Api-Key` header required when `workflow.message-api.api-key` is set.
- `POST /workflow/webhooks/github` — GitHub webhook; re-imports configured Git repos (202, async).
