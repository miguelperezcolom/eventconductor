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
2. If retries remain, the step is parked in `AWAITING_RETRY` for a **backoff delay**, then
   re-dispatched with a new `TaskExecutionRequested` once the delay elapses
3. The attempt count is incremented
4. If all retries are exhausted, the step transitions to `ERROR` and the process to `ERROR`

### Backoff

Retries are **not** re-dispatched immediately — a worker that fails fast (bad config, a downstream
`500`) would otherwise burn the entire retry budget in milliseconds and hammer the failing
dependency in a tight loop, which defeats the purpose of retrying at all. Instead a failed step
waits out an exponentially growing, jittered delay in `AWAITING_RETRY`, and the timeout scheduler
re-dispatches it when the delay expires. Tune it globally:

| Property | Default | Meaning |
|---|---|---|
| `workflow.retry.backoff-base-ms` | `1000` | Delay before the first retry |
| `workflow.retry.backoff-multiplier` | `2.0` | Per-attempt growth (`1.0` = fixed delay) |
| `workflow.retry.backoff-max-ms` | `60000` | Cap on the delay |
| `workflow.retry.backoff-jitter` | `0.2` | ±fraction of randomness, so a fleet that failed together does not retry in lockstep |

A **manual** retry (below) resets straight to `CREATED` with no backoff.

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

:::note[The clock starts when the task reaches a worker]
The timeout measures **execution**, not the wait before it. The engine starts it when the task
actually leaves for a worker — after the dispatch has been relayed out of the outbox — not when the
orchestrator decided the step was ready to run.

That matters under load. A queue that grows is capacity, not failure: if queueing counted against
the timeout, a backlog deeper than the timeout would expire steps before any worker had seen them,
take their retries with them, and roll back their sagas — turning latency into business state left
half-undone. A step waiting for capacity simply waits, and if it waits too long it shows up in
`eventconductor.steps.stalled`, which is the signal built for exactly that.

So `timeout` is a promise about your worker: how long it may take once it has the task. It is not a
deadline on the process as a whole.
:::

## On-timeout routing

By default a timeout, once retries are exhausted, is a **failure**: the step ends `TIMEOUT` and the
process errors (and any *already-succeeded* compensable steps roll back). But a timeout is often not
an error to abort on — it is an *outcome to route on*. A payment-verification task nobody actions in
30 seconds should **cancel the booking**, not fail the process.

Set `onTimeoutStepId` to route forward on timeout instead of failing:

```json
{
  "id": "verify-payment",
  "type": "USER_TASK",
  "formId": "verify-payment",
  "timeout": "PT30S",
  "onTimeoutStepId": "cancel-booking",
  "preconditionStepId": "start"
},
{
  "id": "cancel-booking",
  "type": "ACTION",
  "topic": "booking",
  "preconditions": [ { "stepId": "verify-payment", "expression": "paymentReceived == false" } ]
}
```

When `verify-payment` times out (after any retries), the flow moves to `cancel-booking`; the timed-out
step ends `TIMEOUT` but is **not** counted as a process failure. Here `cancel-booking` is reached two
ways — a human rejection (its precondition guard) or the timeout (`onTimeoutStepId`) — so both paths
converge on the same step. The pending task is cancelled when the process moves on (it disappears from
the worker/forms inbox).

**On-timeout vs compensation.** They are duals. Compensation runs the undo of steps that already
*succeeded*, in reverse, when a process fails — it rolls **backward**. On-timeout routes **forward**
to a step of your choosing, because a step that timed out never succeeded, so there is nothing of *its*
to compensate. Reach for `onTimeoutStepId` when a deadline has a defined next move (cancel, escalate,
take the default); reach for compensation when a failure must unwind committed work. A step can carry
both a `timeout` and a `compensationStepId` for the steps *before* it.

In the graph editor, draw an on-timeout line with **shift+alt+drag** from the timing-out step to its
target (it renders amber with a ⏱ clock chip showing the timeout).

## Compensation (Saga pattern)

For distributed transactions, use `compensable` + `compensationStepId` to define compensation logic:

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
      "compensable": true,
      "compensationStepId": "cancel-hotel",
      "retries": 2
    },
    {
      "id": "reserve-flight",
      "type": "ACTION",
      "name": "Reserve Flight",
      "topic": "flight-service",
      "preconditionStepId": "reserve-hotel",
      "compensable": true,
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

**A compensation step declares no preconditions.** It is named by the step it undoes, and the
rollback pipeline starts it directly, so it needs no way in of its own — and a step with nothing
to wait for never starts by itself (only a `START`, or a `WAIT_FOR_MESSAGE` that begins a flow,
does). Validation knows this: a compensation step is reachable because something names it.

:::note[Written the old way]
Before compensation steps could stand on their own, every step needed a precondition, so they were
anchored to the step they compensate and guarded with `"preconditionExpression": "false"` to keep
the dataflow from starting them. Definitions written that way still work exactly as they did — the
guard is false, so the anchor never fires. The reason to stop writing them is that the anchor had
to be correct every time: an anchor whose guard is missing or misspelled is not a compensation, it
is a live branch of the happy path that fires the moment the step it "compensates" succeeds.
:::

**How it works:** compensation is a **whole-process saga rollback**, not a per-step undo. When
any step fails after exhausting its retries:

1. The process enters `ERROR` and the normal flow is blocked (no new steps start).
2. The engine collects **every step that has executed** (completed, plus the one that just
   failed) and declares a `compensationStepId`, and runs their compensations **sequentially,
   in reverse execution order** — the latest-executed step is undone first. In the example, if
   `reserve-flight` fails, `cancel-flight` runs first, then `cancel-hotel`.
3. Each compensation starts only once the previous one has completed, so the order is strict.
4. When the whole chain completes, the process transitions to the terminal **`COMPENSATED`**
   state (a clean rollback — distinct from a raw `ERROR`). If a compensation step itself fails
   after its own retries, the chain halts and the process reaches the distinct terminal
   **`COMPENSATION_FAILED`** — it is left **partially rolled back**, so it is *not* masked back into
   a plain `ERROR`. This is the most dangerous saga outcome and is never silent: it increments the
   `eventconductor.compensations.failed` metric (the one compensation metric to alert on) and logs
   loudly, so an operator can find the process and resolve the inconsistent state by hand.

A single failed compensable step is just the degenerate case of this cascade: its one
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

If the expression evaluates to `false`, the step does not run and the workflow carries on without
it: nothing else can start it, and the process finishes around it. (The step execution is left
`CANCELLED` rather than `COMPLETED` — see [Statuses](/reference/statuses/).)

### …and the other kind: a condition on one link

`preconditionExpression` gates the step **however it is reached**. When what you mean is "only by
this route", put the condition on the link instead:

```json
{
  "id": "ship",
  "type": "ACTION",
  "preconditions": [
    { "stepId": "pack" },
    { "stepId": "charge", "expression": "amount > 100" }
  ]
}
```

The two behave differently on purpose, and the difference is the whole reason both exist:

| | `preconditionExpression` (on the step) | `expression` (on a link) |
|---|---|---|
| Applies to | the step, by any route | that one incoming link |
| When false | the step is skipped and the process finishes | the link is unsatisfied and the step **waits** |
| Re-evaluated | every tick | every tick |

A step waiting on a false link condition is released as soon as what the condition reads changes.
If nothing ever changes it, the process waits indefinitely — see the caution in
[Step types](/reference/step-types/).

Available in JEXL expressions: all process variables by name.

## Running a stopped process again

A process that stopped — `ERROR`, or `CANCELLED` by an operator — can be put back to work in two
ways, from the process list, from the process detail, or through the MCP tools `retryProcess` and
`restartProcess`:

**Retry from failure** re-dispatches the steps that failed (in a cancelled process, the ones that
were cancelled) and leaves the ones that already succeeded alone. The right choice when the failure
was the environment.

**Restart from the beginning** puts every step back to the state it was created in and runs the
whole process again, the successful steps included, with the variables the process was created
with — re-running from the variables the failed run wrote would not be starting from the beginning.
The right choice when the run itself was wrong. Both re-run the same process instance, keeping its
id and business key, and both use the workflow definition as it was when that process was created.

Programmatically, publish the request rather than calling the use case, so it is carried out by the
node that owns the process:

```java
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new RetryProcessRequested(processId)      // or: new RestartProcessRequested(processId)
));
```

Neither is accepted for a `COMPLETED` or `COMPENSATED` process, or for one still running.

## What this saves you

The three mechanisms on this page — a timeout and bounded retries per step, and compensation
declared as `compensable` plus `compensationStepId` — are fields of a step here because the engine
fixes how they behave. Expressing the same behaviour in a notation that assumes nothing costs a
great deal more: the
[comparison page](/guides/comparison/#what-the-diagram-costs-an-asynchronous-compensating-saga)
models this very saga in both, side by side, with both definitions downloadable.
