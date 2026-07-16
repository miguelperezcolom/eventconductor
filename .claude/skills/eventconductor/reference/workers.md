# Implementing workers

A worker receives a `TaskExecutionRequested`, does work, and reports status + output
variables. Workers are **stateless**; the engine owns retries, timeouts, and error tracking.

```java
record TaskExecutionRequested(
    String taskExecutionId,       // report back with THIS
    String processId, String workflowDefinitionId,
    String stepId, String taskId,
    List<Variable> variables) {}  // process vars here, incl. outputs of earlier steps
```

## Embedded mode

Register one `EmbeddedTaskExecutor` bean and branch on `stepId`:
```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return req -> {
        switch (req.stepId()) {
            case "charge" -> update.handle(new UpdateStepExecutionCommand(
                req.taskExecutionId(), List.of(new Variable("charged", "true")),
                "", StepExecutionStatus.COMPLETED));
            default -> update.handle(new UpdateStepExecutionCommand(
                req.taskExecutionId(), List.of(),
                "Unknown step: " + req.stepId(), StepExecutionStatus.ERROR));
        }
    };
}
```
Alternatively register per-topic beans (bean name = step `topic`): `@Bean("payment-service")`.

## Kafka mode (Spring Cloud Stream)

Consume `TaskExecutionRequested` from `downstream`; publish `TaskStatusChanged` to `upstream`:
```java
record TaskStatusChanged(String taskExecutionId, TaskStatus status,
                         List<Variable> variables, String log) {}

@Bean
Consumer<TaskExecutionRequested> paymentService(StreamBridge bridge) {
    return req -> {
        try {
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.COMPLETED,
                List.of(new Variable("charged", "true")), null));
        } catch (Exception e) {
            bridge.send("upstream", new TaskStatusChanged(
                req.taskExecutionId(), TaskStatus.ERROR, List.of(), e.getMessage()));
        }
    };
}
```

## Progress & async

- Report `RUNNING` (empty outputs, a log message) for long tasks — resets the timeout clock.
- For async work, return now and call `UpdateStepExecutionUseCase` later — it's a Spring bean
  available anywhere in the context.

## Output variables

Reported outputs are **merged into the process variables** (same name overwrites) and become
available to all later steps and to JEXL `preconditionExpression`s.

## Reporting statuses

Workers may report only `RUNNING`, `COMPLETED`, `ERROR`. The engine derives the rest
(`TIMEOUT`, `SKIPPED`, `CANCELLED`) and manages retries.
