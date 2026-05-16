---
title: Kafka Topics
description: Kafka topic reference for EventConductor distributed mode.
---

Applies to `workflow.mode=kafka` only.

## Topic overview

| Topic | Direction | Description |
|---|---|---|
| `outbox` | Internal | Domain events relayed from the outbox table |
| `upstream` | Inbound | Integration events from external services |
| `downstream` | Outbound | Task execution requests sent to workers |

## outbox

**Purpose:** Carries domain events from the outbox table to the event handlers inside the orchestrator. This is an internal topic — external services should not publish to it.

**Published by:** `OutboxRelay` (reads pending events from the DB and publishes here)

**Consumed by:** Orchestrator event handlers (`ProcessCreatedEventHandler`, `StepExecutionStatusChangedEventHandler`, etc.)

**Event types:**
- `ProcessCreated`
- `StepExecutionStatusChanged`
- `ProcessCancellationRequested`

## upstream

**Purpose:** Integration events from external services to the orchestrator.

**Published by:** External services, workers, and the orchestrator itself (for internal events)

**Consumed by:** `ProcessUpstreamEventUseCase`

**Event types (inbound):**

```json
// Start a new process
{
  "@type": "ProcessCreationRequested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" }
  ]
}

// Report task completion
{
  "@type": "TaskStatusChanged",
  "taskExecutionId": "exec-uuid",
  "status": "COMPLETED",
  "variables": [
    { "name": "result", "value": "ok" }
  ]
}

// Cancel a process
{
  "@type": "ProcessCancellationRequested",
  "processId": "process-uuid"
}

// Timeout check (sent by orchestrator)
{
  "@type": "TimeoutCheckRequested"
}
```

## downstream

**Purpose:** Task execution requests dispatched by the orchestrator to workers.

**Published by:** Orchestrator (when an ACTION step is ready to execute)

**Consumed by:** Worker microservices

**Event payload:**

```json
{
  "@type": "TaskExecutionRequested",
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

Workers consume this topic, perform their work, and publish a `TaskStatusChanged` to the `upstream` topic.

## Consumer groups

| Consumer | Group | Topic |
|---|---|---|
| Orchestrator outbox handler | `orchestrator-group` | `outbox` |
| Orchestrator upstream handler | `orchestrator-group` | `upstream` |
| Workers | `worker-group` | `downstream` |

Multiple orchestrator instances in the same group will share the load and coordinate via PostgreSQL advisory locks.
