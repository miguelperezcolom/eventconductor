---
title: Implementing Workers
description: How to implement workers that handle ACTION steps in your workflows.
---

A worker receives a `TaskExecutionRequested` event, performs business logic, and reports back a `TaskStatusChanged` event with the outcome and output variables.

Workers are **stateless** — they receive input variables, do their work, and return output variables. The orchestrator handles retries, timeouts, and error tracking.

## Kafka worker

### Receiving tasks

Subscribe to the `downstream` Kafka topic. The event payload:

```java
record TaskExecutionRequested(
    String taskExecutionId,   // unique ID for this execution
    String processId,          // parent process ID
    String workflowDefinitionId,
    String stepId,
    String taskId,
    List<Variable> variables   // process variables at this point
) {}
```

### Reporting completion

Publish a `TaskStatusChanged` to the `upstream` topic:

```java
record TaskStatusChanged(
    String taskExecutionId,
    TaskStatus status,         // COMPLETED | ERROR | RUNNING
    List<Variable> variables,  // output variables merged into the process
    String log                 // optional log message
) {}
```

### Example (Spring Cloud Stream)

```java
@Component
public class MyWorker {

    @Bean
    public Consumer<TaskExecutionRequested> myWorkerTopic(
            StreamBridge streamBridge) {
        return request -> {
            try {
                String result = doBusinessLogic(request.variables());
                streamBridge.send("upstream", new TaskStatusChanged(
                    request.taskExecutionId(),
                    TaskStatus.COMPLETED,
                    List.of(new Variable("result", result)),
                    null
                ));
            } catch (Exception e) {
                streamBridge.send("upstream", new TaskStatusChanged(
                    request.taskExecutionId(),
                    TaskStatus.ERROR,
                    List.of(),
                    e.getMessage()
                ));
            }
        };
    }
}
```

## Embedded worker (mode: embedded)

When running in embedded mode, register a single Spring bean of type `EmbeddedTaskExecutor`. All ACTION steps are routed to that bean regardless of the `topic` field in the workflow definition. The bean receives the full `TaskExecutionRequested` — use `request.stepId()` to branch between steps. Each branch calls `updateStepExecution` independently with its own output variables.

Output variables produced by a step are automatically included in `request.variables()` for all subsequent steps, so later steps can read values written by earlier ones.

```java
@Bean
public EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase updateStepExecution) {
    return request -> {
        switch (request.stepId()) {
            case "greet" -> {
                String name = request.variables().stream()
                    .filter(v -> "name".equals(v.name()))
                    .map(v -> v.value())
                    .findFirst().orElse("World");
                System.out.println("Hello, " + name + "!");
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(new Variable("greeting", "Hello, " + name + "!")),
                    "",
                    StepExecutionStatus.COMPLETED
                ));
            }
            case "farewell" -> {
                // variables() includes outputs from previous steps (e.g. greeting)
                String name = request.variables().stream()
                    .filter(v -> "name".equals(v.name()))
                    .map(v -> v.value())
                    .findFirst().orElse("World");
                System.out.println("Goodbye, " + name + "!");
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(new Variable("farewell", "Goodbye, " + name + "!")),
                    "",
                    StepExecutionStatus.COMPLETED
                ));
            }
            default -> updateStepExecution.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(),
                List.of(),
                "Unknown step: " + request.stepId(),
                StepExecutionStatus.ERROR
            ));
        }
    };
}
```

A working example with two sequential steps is available in `demo/embedded-headless`.

## Reporting intermediate progress

Workers can report `RUNNING` status to indicate they are still working. This resets the timeout clock and updates the step execution status:

```java
// Kafka mode
streamBridge.send("upstream", new TaskStatusChanged(
    request.taskExecutionId(),
    TaskStatus.RUNNING,
    List.of(),
    "Processing batch 3 of 10..."
));

// Embedded mode
updateStepExecution.handle(new UpdateStepExecutionCommand(
    request.taskExecutionId(),
    List.of(),
    "Processing batch 3 of 10...",
    StepExecutionStatus.RUNNING
));
```

## Asynchronous workers

For long-running tasks, the worker can return immediately and report completion later. `UpdateStepExecutionUseCase` is a Spring bean available anywhere in the application context:

```java
@Component
@RequiredArgsConstructor
public class AsyncWorkerBean {

    private final UpdateStepExecutionUseCase updateStepExecution;

    public void startAsync(TaskExecutionRequested request) {
        CompletableFuture.runAsync(() -> {
            // ... long-running work ...
            updateStepExecution.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(),
                List.of(new Variable("result", "done")),
                "",
                StepExecutionStatus.COMPLETED
            ));
        });
    }
}
```

## Output variables

Output variables reported by a worker are **merged into the process variables**. They overwrite any existing variable with the same name and are available to all subsequent steps.

```java
// These variables will be accessible in later JEXL precondition expressions
List.of(
    new Variable("approved", "true"),
    new Variable("approvedBy", "manager@example.com")
)
```
