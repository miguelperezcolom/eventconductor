# Java API

Inject these Spring beans anywhere in the application context.

## Entry points

```java
// Start / cancel a process (any deployment mode)
@Autowired ProcessUpstreamEventUseCase processUpstreamEventUseCase;

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("workflow-id", "business-key",
        List.of(new Variable("k", "v")))));

processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCancellationRequested(processId)));   // running steps → CANCELLED

// Report worker progress
@Autowired UpdateStepExecutionUseCase updateStepExecution;

updateStepExecution.handle(new UpdateStepExecutionCommand(
    taskExecutionId,                    // from TaskExecutionRequested
    List.of(new Variable("result","ok")),
    "optional log",
    StepExecutionStatus.COMPLETED));    // RUNNING | COMPLETED | ERROR
```

## Repositories

```java
ProcessRepository:            findById, findByBusinessKey, findAll, findByStatus(ProcessStatus)
StepExecutionRepository:      findByProcessId, findByStatus(StepExecutionStatus)
WorkflowDefinitionRepository: findById, findByStatus(WorkflowDefinitionStatus), save
```

## Records & interfaces

```java
record Variable(String name, String value) {}

@FunctionalInterface
interface EmbeddedTaskExecutor { void execute(TaskExecutionRequested request); }
```

## Domain model (selected fields)

- `Process`: `id`, `workflowDefinitionId`, `businessKey`, `status`, `variables`, `createdAt`, `updatedAt`.
- `StepExecution`: `id`, `processId`, `stepId`, `taskExecutionId`, `status`, `retryCount`, `startedAt`, `completedAt`, `log`.
- `WorkflowDefinition`: `id`, `name`, `version`, `status`, `steps`.

## Statuses

- **Process**: `PENDING → RUNNING → COMPLETED | ERROR`; `→ CANCELLED`. `ERROR` is retriable.
- **StepExecution**: `CREATED → PENDING → RUNNING → COMPLETED | ERROR`; `TIMEOUT → PENDING (retry) | ERROR`; `SKIPPED`; `CANCELLED`.

## Kafka (mode: kafka)

- `upstream` — inbound: `ProcessCreationRequested`, `ProcessCancellationRequested`, `TaskStatusChanged`.
- `downstream` — outbound to workers: `TaskExecutionRequested`.
- `outbox` — internal relay.
