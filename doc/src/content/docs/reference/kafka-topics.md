---
title: Kafka Topics
description: Kafka topic reference for EventConductor distributed mode.
---

Applies to `workflow.mode=kafka` only.

Events are serialized as JSON with a `type` property carrying the registered event id (kebab-case, as declared on `DomainEvent`) — for example `"type": "process-creation-requested"`.

## Topic overview

| Topic | Direction | Description |
|---|---|---|
| `outbox` | Internal | Domain events relayed from the outbox table |
| `upstream` | Inbound | Integration events from external services |
| `downstream` | Outbound | Task execution requests sent to workers |

## outbox

**Purpose:** Carries domain events from the outbox table to the event handlers inside the orchestrator. This is an internal topic — external services should not publish to it.

**Published by:** `OutboxRelay` (reads pending events from the DB and publishes here)

**Consumed by:** Orchestrator event handlers (`ProcessCreatedEventHandler`, `StepExecutionStatusUpdatedEventHandler`, etc.)

**Event types:**
- `process-created`
- `step-execution-status-changed`
- `step-execution-creation-requested`

## upstream

**Purpose:** Integration events from external services to the orchestrator.

**Published by:** External services, workers, and the orchestrator itself (for internal events)

**Consumed by:** `ProcessUpstreamEventUseCase`

**Event types (inbound):**

```json
// Start a new process
{
  "type": "process-creation-requested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" }
  ]
}

// Deliver a message to waiting WAIT_FOR_MESSAGE steps
{
  "type": "message-received",
  "messageName": "payment-received",
  "correlationKey": "order-123",
  "variables": [
    { "name": "paymentId", "value": "P-9" }
  ]
}

// Report task completion
{
  "type": "task-status-changed",
  "taskExecutionId": "exec-uuid",
  "status": "COMPLETED",
  "variables": [
    { "name": "result", "value": "ok" }
  ]
}

// Append a log line to a task execution
{
  "type": "task-log-emitted",
  "taskExecutionId": "exec-uuid",
  "messageType": "Info",
  "message": "40% done"
}

// Timeout check (sent by the orchestrator's scheduler)
{
  "type": "timeout-check-requested",
  "processId": "process-uuid"
}

// Timer check for due TIMER steps (sent by the orchestrator's scheduler)
{
  "type": "timer-check-requested",
  "processId": "process-uuid"
}
```

A `message-received` event resumes processes waiting on a `WAIT_FOR_MESSAGE` step with the same `messageName` and correlation key. Alternatives to raw Kafka delivery: `POST /workflow/api/messages`, the `sendMessage` MCP tool, a `SEND_MESSAGE` step in another workflow, or programmatically via `ProcessUpstreamEventUseCase` — see [Step Types — WAIT_FOR_MESSAGE](/reference/step-types/#wait_for_message).

:::note[Cancellation is not an upstream event]
Publishing a `process-cancellation-requested` event on this topic has no effect — no upstream handler consumes it. To cancel a process, call `CancelProcessUseCase` (or use the management UI / MCP tools) — see [Starting a Process — Cancelling a process](/guides/starting-a-process/#cancelling-a-process).
:::

## downstream

**Purpose:** Task execution requests dispatched by the orchestrator to workers.

**Published by:** Orchestrator (when an ACTION step is ready to execute)

**Consumed by:** Worker microservices

**Event payload:**

```json
{
  "type": "task-execution-requested",
  "taskExecutionId": "exec-uuid",
  "processId": "process-uuid",
  "workflowDefinitionId": "my-workflow",
  "stepId": "process-payment",
  "taskId": "task-uuid",
  "variables": [
    { "name": "orderId", "value": "123" },
    { "name": "amount", "value": "99.90" }
  ]
}
```

Workers consume this topic, perform their work, and publish a `task-status-changed` to the `upstream` topic.

## Consumer groups

| Consumer | Group | Topic |
|---|---|---|
| Orchestrator outbox handler | `orchestrator-group` | `outbox` |
| Orchestrator upstream handler | `orchestrator-group` | `upstream` |
| Workers | `worker-group` | `downstream` |

Multiple orchestrator instances in the same group will share the load and coordinate via database advisory locks — the lock dialect is auto-detected from the JDBC connection (PostgreSQL, MariaDB/MySQL, or Oracle; see the [configuration reference](/reference/configuration/#distributed-locking)).
