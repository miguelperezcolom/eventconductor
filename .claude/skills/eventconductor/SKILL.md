---
name: eventconductor
description: Design and run business processes with EventConductor — the Java/Spring event-driven workflow & saga orchestration engine (io.mateu.workflow). Use when defining a workflow (JSON/YAML steps), implementing workers, starting/cancelling/querying processes, adding retries/timeouts/compensation (sagas), human USER_TASK forms, durable waits (TIMER), external-message waits (MESSAGE), business rules (RULE), or child (PROCESS) workflows. Triggers on workflow-engine, WorkflowDefinition, ProcessUpstreamEventUseCase, UpdateStepExecutionUseCase, EmbeddedTaskExecutor, TaskExecutionRequested, ACTION/USER_TASK/RULE/TIMER/MESSAGE/FORK/JOIN steps, workflow.mode, workflow.persistence.
---

# Orchestrating processes with EventConductor

EventConductor turns a **workflow definition** (JSON or YAML) into a running state
machine. You write **what the process is** — a list of steps and their preconditions —
and the engine schedules steps, enforces retries/timeouts, and runs compensation.
**Business logic lives in workers**, never in the engine.

## The one rule

Describe the process as **data** (a definition with `steps`), and put logic in **workers**
(stateless). The engine owns orchestration; workers own work. Never hand-roll a state
machine, a scheduler, or retry/timeout loops — declare them on the step.

## Pick the pattern

| You want | Use | Reference |
|---|---|---|
| a sequence of automated steps | `ACTION` steps chained by `preconditionStepId` | [workflow-definitions.md](reference/workflow-definitions.md) |
| a step that calls your code | a worker (`EmbeddedTaskExecutor` or Kafka consumer) | [workers.md](reference/workers.md) |
| a human approval / data entry | `USER_TASK` step + a form (`formId`) | [workflow-definitions.md](reference/workflow-definitions.md) |
| a business rule / decision table | `RULE` step + a rule (`ruleId`) evaluated by rule-runtime | [workflow-definitions.md](reference/workflow-definitions.md) |
| a durable wait (delay / until a date) | `TIMER` step (`duration` or `untilVariable`) | [workflow-definitions.md](reference/workflow-definitions.md) |
| wait for an external event/callback | `MESSAGE` step (`messageName`, optional `correlationExpression`) | [workflow-definitions.md](reference/workflow-definitions.md) |
| parallel work then a barrier | `FORK` + `parallel: true` steps + `JOIN` | [workflow-definitions.md](reference/workflow-definitions.md) |
| a reusable sub-process | `PROCESS` step (`childWorkflowDefinitionId`) | [workflow-definitions.md](reference/workflow-definitions.md) |
| undo-on-failure (saga) | `rollbackable: true` + `compensationStepId` | [workflow-definitions.md](reference/workflow-definitions.md) |
| start / cancel / query a process | `ProcessUpstreamEventUseCase`, `ProcessRepository` | [api.md](reference/api.md) |
| conditional / skipped steps | `preconditionExpression` (JEXL over variables) | [workflow-definitions.md](reference/workflow-definitions.md) |

Common mistakes: [gotchas.md](reference/gotchas.md). Deployment topology & config:
use the `eventconductor-scaffold` skill. Running/verifying a change: `eventconductor-run`.

## Skeleton — a process is a definition + workers

`classpath:/workflows/order.json`:
```json
{
  "id": "order-processing", "name": "Order Processing", "version": 1, "status": "ACTIVE",
  "steps": [
    { "id": "validate", "type": "ACTION", "name": "Validate", "topic": "order-validator" },
    { "id": "charge",   "type": "ACTION", "name": "Charge",   "topic": "payment-service",
      "preconditionStepId": "validate", "timeout": "PT30S", "retries": 2 },
    { "id": "end", "type": "END", "name": "Done", "preconditionStepId": "charge" }
  ]
}
```

A worker for a step (embedded mode). Import
`io.mateu.workflow.domain.aggregates.Variable` here (`UpdateStepExecutionCommand`); the events
(`TaskExecutionRequested`, `ProcessCreationRequested`, `TaskStatusChanged`) use
`io.mateu.workflow.dtos.Variable` instead:
```java
@Bean
EmbeddedTaskExecutor taskExecutor(UpdateStepExecutionUseCase update) {
    return req -> update.handle(new UpdateStepExecutionCommand(
        req.taskExecutionId(),                       // report with taskExecutionId, NOT stepId
        List.of(new Variable("charged", "true")),    // outputs merge into process variables
        "", StepExecutionStatus.COMPLETED));         // RUNNING | COMPLETED | ERROR
}
```

Start it:
```java
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCreationRequested("order-processing", "order-123",
        List.of(new Variable("amount", "99.90")))));
```

## Conventions for generated code

- **Steps run by data flow, not array order.** A step starts when its `preconditionStepId`
  step completes; a step with no precondition starts immediately.
- **Exactly one `END`.** With parallel branches, funnel them through a `JOIN` before `END`.
- **Variables are `(name, value)` strings.** Worker outputs are merged in and readable by
  later steps and by JEXL `preconditionExpression`s (`"amount > 1000"`).
- **Report with `taskExecutionId`** (from `TaskExecutionRequested`), never the `stepId`.
- **Embedded mode ignores `topic`** (one `EmbeddedTaskExecutor`, branch on `stepId`, or use
  per-`topic` named beans). **Kafka mode requires `topic`** on every `ACTION`.
- **Declare, don't code, resilience:** `timeout` (ISO-8601 `PT30S` or ms), `retries: N`,
  and `rollbackable`+`compensationStepId` for sagas.
- Keep business logic in workers / use cases; the definition only expresses orchestration.

## Why this is good for AI codegen

The process is data (few tokens, reviewable in a PR, schema-validated), and the only code
is small, stateless workers with a single responsibility. Given the definition, execution
is deterministic. Generate the definition; implement thin workers; let the engine drive.

## Canonical references

This skill is self-contained, but the always-current sources of truth in this repo are
`doc/public/eventconductor-ai-compact.md` and `doc/public/eventconductor-ai-full.md`, the
docs under `doc/src/content/docs/`, and the JSON Schema at
`modules/workflow-engine/src/main/resources/workflow-definition-schema.json`. Prefer them
if anything here looks out of date.
