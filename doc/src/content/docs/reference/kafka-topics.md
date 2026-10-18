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
| `http-calls` | Outbound | `HTTP_CALL` requests, served by `http-worker-standalone-app` (a step's `topic` overrides) |
| `llm-calls` | Outbound | `LLM_CALL` prompts, served by `llm-worker-standalone-app` (a step's `topic` overrides) |
| `dead-letter` | Outbound | Events the engine can never process, parked unchanged for inspection and replay |
| `process-index` | Internal | Process status changes for the standalone projector (`workflow.projection.mode=remote` only) |
| `human-tasks` | Outbound | Every change of a human task (opened, assigned, completed, cancelled), published by `forms-standalone-app` (`FORMS_HUMAN_TASKS_TOPIC`) for inboxes and notification centres; nothing in EventConductor reads it |
| `rules` | Internal | Rule catalog changes (`rule-published`, `rule-deleted`) published by `rule-standalone-app`, for rule runtimes that keep a local cache (`rules.kafka-refresh=true`, binding `consumeRuleCatalogEvent-in-0`) |
| *destination topics* | Inbound | Domain events (CloudEvents) on the topics of `workflow.events.destinations.<name>.topic`, for event triggers and `onEvent` waits |

The engine contributes the `outbox`, `upstream`, `downstream`, `dead-letter` and `process-index` bindings (and the orchestrator's two consumer groups) as lowest-precedence defaults, so an application only sets them to change them.

## outbox

**Purpose:** Carries domain events from the outbox table to the event handlers inside the orchestrator. This is an internal topic — external services should not publish to it.

**Published by:** `OutboxRelay` (reads pending events from the DB and publishes here)

**Consumed by:** Orchestrator event handlers (`ProcessCreatedEventHandler`, `StepExecutionStatusUpdatedEventHandler`, etc.)

**Event types (among others):**
- `process-created`
- `step-execution-status-changed`
- `task-execution-requested`
- `process-status-changed`
- `message-subscription-changed`

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

// Operator commands on one process (what the UI, REST and MCP send in kafka mode)
{ "type": "process-cancellation-requested", "processId": "process-uuid" }
{ "type": "pause-process-requested",        "processId": "process-uuid" }
{ "type": "resume-process-requested",       "processId": "process-uuid" }
{ "type": "restart-process-requested",      "processId": "process-uuid" }
{ "type": "retry-process-requested",        "processId": "process-uuid" }
{ "type": "retry-step-execution-requested", "processId": "process-uuid", "stepExecutionId": "exec-uuid" }

// Steps a DYNAMIC step injects into its process
{
  "type": "steps-injected",
  "taskExecutionId": "exec-uuid",
  "processId": "process-uuid",
  "stepsJson": "[ { \"id\": \"extra\", \"type\": \"ACTION\", \"topic\": \"svc\" } ]"
}
```

A `message-received` event resumes processes waiting on a `WAIT_FOR_MESSAGE` step with the same `messageName` and correlation key. Alternatives to raw Kafka delivery: `POST /workflow/api/messages`, the `sendMessage` MCP tool, a `SEND_MESSAGE` step in another workflow, or programmatically via `ProcessUpstreamEventUseCase` — see [Step Types — WAIT_FOR_MESSAGE](/reference/step-types/#wait_for_message).

The operator commands are handled by `CancelProcessRequestedEventHandler`, `PauseProcessRequestedEventHandler`, `ResumeProcessRequestedEventHandler`, `RestartProcessRequestedEventHandler`, `RetryProcessRequestedEventHandler` and `RetryStepExecutionRequestedEventHandler`, which call the same use cases as the UI and MCP tools (`CancelProcessUseCase`, …); `steps-injected` is handled by `StepsInjectedEventHandler`. The commands address the process by `processId`; the optional `businessKey` of a `process-cancellation-requested` is not used to find it. See [Starting a Process — Cancelling a process](/guides/starting-a-process/#cancelling-a-process).

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

## http-calls and llm-calls

**Purpose:** The rendered requests of `HTTP_CALL` steps and the rendered prompts of `LLM_CALL` steps. They are the default topics for those steps; a step's `topic` sends its requests elsewhere.

**Published by:** Orchestrator

**Consumed by:** `http-worker-standalone-app` (`http-calls`, overridable with `HTTP_CALLS_TOPIC`) and `llm-worker-standalone-app` (`llm-calls`, overridable with `LLM_CALLS_TOPIC`), which report back on `upstream` like any worker.

## dead-letter

**Purpose:** Where an event the engine can never process is parked — one that is itself defective, or a record that could not even be read. The payload is the original event (or, for an unreadable record, its original bytes), untouched; the failure travels in headers — `x-dead-letter-source` (where it came from), `x-dead-letter-reason`, `x-dead-letter-message`, plus `x-dead-letter-process` for a readable event (which also keeps its process partition key) or `x-dead-letter-unreadable: true`. Replaying a dead letter is republishing it on the destination its `x-dead-letter-source` names.

**Published by:** Orchestrator

**Consumed by:** Nobody in EventConductor — it is for operators and tooling.

## process-index

**Purpose:** With `workflow.projection.mode=remote`, the outbox relay diverts process status changes here instead of projecting them locally, so the standalone projector maintains one fleet-wide process index. Unused in the default (embedded projection) mode. See [Process index](/guides/process-index/).

**Published by:** Orchestrator (every shard)

**Consumed by:** `projector-standalone-app` (topic overridable with `PROJECTION_TOPIC`)

## Domain event topics

**Purpose:** CloudEvents from other systems that start processes (event triggers) or resume `onEvent` waits. The topics are not fixed: they are whatever `workflow.events.destinations.<name>.topic` names, for every destination some definition's trigger or wait listens to, recomputed as definitions change.

**Published by:** Other systems, `PUBLISH_EVENT` steps, or the [webhook gateway](/reference/configuration/#webhook-gateway-workflowwebhooks).

**Consumed by:** The inbound domain-event consumer, which is in the `io.mateu.workflow:inbound-events-kafka` module (included in `orchestrator-standalone-app`). See [Starting on and waiting for domain events](/guides/domain-event-triggers/).

## Consumer groups

| Consumer | Group | Topic |
|---|---|---|
| Orchestrator outbox handler | `orchestrator-outbox` | `outbox` |
| Orchestrator upstream handler | `orchestrator-upstream` | `upstream` |
| Orchestrator inbound domain events (`inbound-events-kafka`) | `eventconductor-inbound-events` (`workflow.events.consumer.group`) | the destination topics |
| Projector (`projector-standalone-app`) | `process-index-projector` (`PROJECTION_GROUP`) | `process-index` |
| HTTP worker (`http-worker-standalone-app`) | `http-worker` | `http-calls` |
| LLM worker (`llm-worker-standalone-app`) | `llm-worker` | `llm-calls` |
| Workers (`worker-standalone-app`) | `worker-app-group` | `downstream` |
| Worker embedded in `orchestrator-standalone-app` | `worker-group` | `downstream` |
| Worker in an application of your own (`worker-kafka`) | `${spring.application.name:eventconductor-worker}` | `downstream` |
| Forms engine (`forms-standalone-app`) | `forms-engine-group` | `downstream` |
| Rule runtime (`rule-standalone-app`) | `rule-engine-group` | `downstream` |

The orchestrator's two groups are separate on purpose: one group whose members subscribe to different topics is assigned per topic by the default range assignor, and with mixed subscriptions it leaves partitions with no consumer at all.

Multiple orchestrator instances in the same group share the load: events are keyed by process, so the consumer group gives each process's partition to exactly one instance (a single writer per process), fenced by an optimistic-locking version on the aggregates. The singleton background jobs (timeout scan, cron) still coordinate through a short-lived database advisory lock; see the [configuration reference](/reference/configuration/#distributed-locking).
