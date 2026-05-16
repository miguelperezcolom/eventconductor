---
title: Java API
description: Public Spring beans and interfaces exposed by the workflow-engine module.
---

## Maven dependency

```xml
<dependency>
    <groupId>io.mateu.workflow</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Entry points

### `ProcessUpstreamEventUseCase`

The main entry point for all integration events. Handles process creation, cancellation, task status updates, and timeout checks.

```java
@Autowired
ProcessUpstreamEventUseCase processUpstreamEventUseCase;

// Start a process
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested(
        "workflow-id",
        "business-key",
        List.of(new Variable("key", "value"))
    )
));

// Cancel a process
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCancellationRequested(processId)
));
```

### `UpdateStepExecutionUseCase`

Reports task progress from workers. Available as a Spring bean — inject it anywhere.

```java
@Autowired
UpdateStepExecutionUseCase updateStepExecution;

updateStepExecution.handle(new UpdateStepExecutionCommand(
    taskExecutionId,
    List.of(new Variable("result", "ok")),
    "Optional log message",
    StepExecutionStatus.COMPLETED
));
```

Valid `StepExecutionStatus` values for reporting: `RUNNING`, `COMPLETED`, `ERROR`.

## Repositories

### `ProcessRepository`

```java
@Autowired
ProcessRepository processRepository;

// Find by ID
Process process = processRepository.findById(processId);

// Find by business key
Process process = processRepository.findByBusinessKey("order-123");

// List all
List<Process> processes = processRepository.findAll();

// List by status
List<Process> running = processRepository.findByStatus(ProcessStatus.RUNNING);
```

### `StepExecutionRepository`

```java
@Autowired
StepExecutionRepository stepExecutionRepository;

// Find by process
List<StepExecution> steps = stepExecutionRepository.findByProcessId(processId);

// Find by status
List<StepExecution> pending = stepExecutionRepository.findByStatus(StepExecutionStatus.PENDING);
```

### `WorkflowDefinitionRepository`

```java
@Autowired
WorkflowDefinitionRepository workflowDefinitionRepository;

// Find by ID
WorkflowDefinition def = workflowDefinitionRepository.findById("my-workflow");

// List all active
List<WorkflowDefinition> active = workflowDefinitionRepository
    .findByStatus(WorkflowDefinitionStatus.ACTIVE);

// Save/update
workflowDefinitionRepository.save(definition);
```

## Embedded worker interface

```java
@FunctionalInterface
public interface EmbeddedTaskExecutor {
    void execute(TaskExecutionRequested request);
}
```

Register as a Spring bean with the bean name matching the step's `topic` field:

```java
@Bean("my-topic-name")
public EmbeddedTaskExecutor myWorker(UpdateStepExecutionUseCase updateStepExecution) {
    return request -> {
        // ... do work ...
        updateStepExecution.handle(new UpdateStepExecutionCommand(
            request.taskExecutionId(),
            List.of(),
            "",
            StepExecutionStatus.COMPLETED
        ));
    };
}
```

## Domain model

### `Process`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique process ID |
| `workflowDefinitionId` | String | ID of the workflow definition |
| `businessKey` | String | Optional human-readable identifier |
| `status` | ProcessStatus | Current status |
| `variables` | List\<Variable\> | Process variables |
| `createdAt` | Instant | Creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### `StepExecution`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique step execution ID |
| `processId` | String | Parent process ID |
| `stepId` | String | Step ID from the workflow definition |
| `taskExecutionId` | String | ID sent to workers (use this for `UpdateStepExecutionCommand`) |
| `status` | StepExecutionStatus | Current status |
| `retryCount` | int | Number of retries remaining |
| `startedAt` | Instant | When the step started |
| `completedAt` | Instant | When the step completed |
| `log` | String | Last log message from the worker |

### `Variable`

```java
record Variable(String name, String value) {}
```

### `WorkflowDefinition`

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique workflow ID |
| `name` | String | Human-readable name |
| `version` | int | Version number |
| `status` | WorkflowDefinitionStatus | DRAFT, ACTIVE, DISABLED, ARCHIVED |
| `steps` | List\<Step\> | Step definitions |
