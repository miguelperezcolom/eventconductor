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

Set the `timeout` field (in milliseconds) to limit how long a step can run.

```json
{
  "id": "external-api-call",
  "type": "ACTION",
  "name": "Call External API",
  "topic": "api-service",
  "timeout": 30000,
  "retries": 2
}
```

When a step times out:
1. The step transitions to `TIMEOUT`
2. If retries are configured, it is re-dispatched
3. If no retries remain, it transitions to `ERROR`

`timeout: 0` means no timeout (the default).

## Compensation (Saga pattern)

For distributed transactions, use `rollbackable` + `compensationStepId` to define compensation logic:

```json
{
  "steps": [
    {
      "id": "reserve-hotel",
      "type": "ACTION",
      "name": "Reserve Hotel",
      "topic": "hotel-service",
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
      "topic": "hotel-service"
    },
    {
      "id": "cancel-flight",
      "type": "ACTION",
      "name": "Cancel Flight",
      "topic": "flight-service"
    }
  ]
}
```

**How it works:**
1. If `reserve-flight` fails after exhausting retries, the engine triggers `cancel-hotel` (the compensation step of the previously completed `reserve-hotel`)
2. Compensation steps run in reverse order of the completed rollbackable steps
3. The process transitions to `ERROR` after compensation completes

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
