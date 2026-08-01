---
title: Retries, Timeouts & Compensation
description: Handling failures, timeouts, and saga compensation in EventConductor.
---

EventConductor provides built-in support for retries, timeouts, and compensation (saga pattern) — all configured declaratively in the workflow definition JSON.

## Retries

Set the `retries` field on a step to automatically re-dispatch the task when it reports an `ERROR` or times out.

```json
{
  "id": "charge-payment",
  "type": "ACTION",
  "name": "Charge Payment",
  "topic": "payment-service",
  "retries": 3
}
```

When a step fails:
1. The orchestrator checks the retry count
2. If retries remain, a new `TaskExecutionRequested` is dispatched
3. The retry count is decremented
4. If all retries are exhausted, the step transitions to `ERROR` and the process to `ERROR`

## Timeouts

Set the `timeout` field to limit how long a step can run. Accepts an **ISO 8601 duration string** or an integer in milliseconds (legacy):

| Format | Example | Meaning |
|---|---|---|
| ISO 8601 string | `"PT30S"` | 30 seconds |
| ISO 8601 string | `"PT5M"` | 5 minutes |
| ISO 8601 string | `"PT1H30M"` | 1 hour 30 minutes |
| Integer (ms) | `30000` | 30 seconds (legacy, still supported) |

```json
{
  "id": "external-api-call",
  "type": "ACTION",
  "name": "Call External API",
  "topic": "api-service",
  "timeout": "PT30S",
  "retries": 2
}
```

When a step times out:
1. The step transitions to `TIMEOUT`
2. If retries are configured, it is re-dispatched
3. If no retries remain, it transitions to `ERROR`

`timeout: 0` (or omitting it) means no timeout.

## Compensation (Saga pattern)

For distributed transactions, use `rollbackable` + `compensationStepId` to define compensation logic:

```json
{
  "steps": [
    {
      "id": "start",
      "type": "START",
      "name": "Start"
    },
    {
      "id": "reserve-hotel",
      "type": "ACTION",
      "name": "Reserve Hotel",
      "topic": "hotel-service",
      "preconditionStepId": "start",
      "rollbackable": true,
      "compensationStepId": "cancel-hotel",
      "retries": 2
    },
    {
      "id": "reserve-flight",
      "type": "ACTION",
      "name": "Reserve Flight",
      "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "rollbackable": true,
      "compensationStepId": "cancel-flight"
    },
    {
      "id": "cancel-hotel",
      "type": "ACTION",
      "name": "Cancel Hotel",
      "topic": "hotel-service",
      "preconditionStepId": "reserve-hotel",
      "preconditionExpression": "false"
    },
    {
      "id": "cancel-flight",
      "type": "ACTION",
      "name": "Cancel Flight",
      "topic": "flight-service",
      "preconditionStepId": "reserve-flight",
      "preconditionExpression": "false"
    }
  ]
}
```

Compensation steps need a precondition like every other step (the [roots rule](/guides/workflow-definitions/#validation-at-load)):
anchor each one to the step it compensates and guard it with `"preconditionExpression": "false"`,
so the normal dataflow never starts it. The compensation pipeline starts it directly — without
evaluating the guard — during rollback.

**How it works:** compensation is a **whole-process saga rollback**, not a per-step undo. When
any step fails after exhausting its retries:

1. The process enters `ERROR` and the normal flow is blocked (no new steps start).
2. The engine collects **every step that has executed** (completed, plus the one that just
   failed) and declares a `compensationStepId`, and runs their compensations **sequentially,
   in reverse execution order** — the latest-executed step is undone first. In the example, if
   `reserve-flight` fails, `cancel-flight` runs first, then `cancel-hotel`.
3. Each compensation starts only once the previous one has completed, so the order is strict.
4. When the whole chain completes, the process transitions to the terminal **`COMPENSATED`**
   state (a clean rollback — distinct from a raw `ERROR`). If a compensation itself fails after
   its own retries, the chain halts and the process stays `ERROR`.

A single failed rollbackable step is just the degenerate case of this cascade: its one
compensation runs and the process ends `COMPENSATED`.

## Conditional skipping

Use `preconditionExpression` (a [JEXL](https://commons.apache.org/proper/commons-jexl/) expression) to skip steps based on process variables:

```json
{
  "id": "manual-review",
  "type": "USER_TASK",
  "name": "Manual Review Required",
  "formId": "review-form",
  "preconditionStepId": "validate",
  "preconditionExpression": "amount > 1000 && riskScore == 'HIGH'"
}
```

If the expression evaluates to `false`, the step is **skipped** (transitions directly to `COMPLETED`) and the workflow continues.

Available in JEXL expressions: all process variables by name.

## Retrying a failed process manually

When a process is in `ERROR` state, you can retry all failed steps via the MCP tool `retryProcess` (natural language) or programmatically:

```java
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessRetryRequested(processId)
));
```

This re-dispatches all `ERROR` step executions as new `TaskExecutionRequested` events.
