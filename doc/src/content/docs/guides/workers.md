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

Reply through `WorkerReply`, not through `StreamBridge` directly — see
[Do not drop the reply](#do-not-drop-the-reply) below.

```java
@Component
public class MyWorker {

    @Bean
    public Consumer<TaskExecutionRequested> myWorkerTopic(
            StreamBridge streamBridge) {
        return request -> {
            try {
                String result = doBusinessLogic(request.variables());
                WorkerReply.completed(streamBridge, request,
                    List.of(new Variable("result", result)));
            } catch (Exception e) {
                WorkerReply.failed(streamBridge, request, List.of());
            }
        };
    }
}
```

### Do not drop the reply

`StreamBridge.send` reports failure by **returning `false`**, and the obvious one-liner throws that
away:

```java
// Wrong, and it is the version everyone writes first.
streamBridge.send("upstream", new TaskStatusChanged(...));
```

When the broker refuses the message the listener still returns normally, the consumer commits the
offset, and the task your worker actually performed is never reported. The engine's step stays in
`PENDING` waiting for an answer that was never published, and if that step declares no timeout it
waits forever. This is not hypothetical: during a ninety-second broker outage on a test cluster,
workers written this way lost 3,352 replies and left 3,356 processes permanently stuck, with no
error logged anywhere. See [Reliability](/guides/reliability/).

`WorkerReply` retries a refused send and then **throws**, which is the point: the offset is not
committed, so Kafka redelivers the task and your worker does it again. **Worker handlers must be
idempotent** — they always had to be, because at-least-once delivery was always the contract.

It rests on one setting — an asynchronous producer returns `true` the moment the record is
buffered, so the refusal `WorkerReply` checks for never arrives:

```yaml
spring:
  cloud:
    stream:
      kafka:
        default:
          producer:
            sync: true      # without it, send() returns true before the broker has seen anything
```

You do not have to declare it. It ships with the `shared` module — the one every worker already
depends on to build a `TaskStatusChanged` — and applies wherever a Kafka producer exists. Set it
yourself only if you want the opposite, and know why: it is contributed at the lowest precedence,
so an explicit value always wins.

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

### The thread the worker runs on

By default the engine calls the bean and waits, **on the thread that dispatched the task**. With
`workflow.persistence=jpa` that is `embedded-outbox-relay`, the single thread draining the outbox
and therefore the only one advancing every process in the JVM. A worker that blocks there stops
all of them — and the symptom does not look like a stuck worker: processes created afterwards sit
with every step in `CREATED`, described in the UI as "waiting for its preconditions".

So an embedded worker must not block indefinitely. Two ways out, and they compose:

- Give every outbound call a timeout. A `RestClient` built with `builder.baseUrl(url).build()` has
  none — the connect and read timeouts have to be set on the request factory.
- Set `workflow.embedded.worker-threads` above zero to hand tasks to a pool, or do the handoff
  yourself (see [Asynchronous workers](#asynchronous-workers)). Read
  [the configuration reference](/reference/configuration/#where-an-embedded-worker-runs) first:
  through a pool, a task lost to a crash is recovered by the step's `timeout` rather than by
  redelivery, so give ACTION steps one.

An exception that escapes the bean fails the step — the engine reports the `ERROR` you did not.
Prefer reporting it yourself: a throw carries no output variables and no message of your choosing.

## Reporting intermediate progress

Workers can report `RUNNING` status to indicate they are still working. This resets the timeout clock and updates the step execution status:

```java
// Kafka mode
WorkerReply.running(streamBridge, request);

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
