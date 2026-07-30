---
title: Process & Step Statuses
description: All status values for process instances and step executions.
---

## Process status

| Status | Description |
|---|---|
| `PENDING` | Created, not yet started — waiting for the orchestration loop |
| `RUNNING` | At least one step is currently executing |
| `COMPLETED` | All steps finished successfully |
| `ERROR` | A step failed after exhausting all retries |
| `CANCELLED` | Process was cancelled by an operator or a cancellation event |

### Status transitions

```
PENDING → RUNNING → COMPLETED
                  → ERROR
         RUNNING → CANCELLED
PENDING → CANCELLED
```

A process in `ERROR` can be retried, which transitions it back to `RUNNING`.

---

## Step execution status

| Status | Description |
|---|---|
| `CREATED` | Scheduled by the orchestrator, waiting for the next orchestration loop tick — or waiting for its precondition to become true |
| `PENDING` | Task dispatched to worker, awaiting acknowledgement |
| `RUNNING` | Worker reported it has started processing |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout after retries exhausted |
| `TIMEOUT` | Step exceeded its configured `timeout` — will retry if retries remain |
| `CANCELLED` | Step was never run or was stopped: process cancellation, saga compensation, or process completion while the step was still waiting |

### Status transitions

```
CREATED → PENDING → RUNNING → COMPLETED
                            → ERROR
                  → TIMEOUT → CREATED (retry, if attempts remain)
                            → ERROR (if no retries remain)
ERROR   → CREATED (retry — automatic if attempts remain, or manual process retry)
CREATED → CANCELLED (END step reached, or implicit completion, or process cancelled)
PENDING → CANCELLED
RUNNING → CANCELLED
```

A retry (automatic while attempts remain, or a manual process retry) resets the step to `CREATED`, from where it is dispatched again on the next orchestration tick.

### Preconditions: there is no "skipped" status

A step whose guard does not hold — its `preconditionStepId` has not `COMPLETED`, or its `preconditionExpression` evaluates to a falsy value (or fails to evaluate; guards fail closed) — is **not** skipped. It simply never starts: it stays in `CREATED` and the guard is re-evaluated on every orchestration tick, so it can still run later if the guard becomes true. When the process finishes — an `END` step fires, or the process completes implicitly because no runnable steps remain — every step still in `CREATED`, `PENDING`, or `RUNNING` is transitioned to `CANCELLED`.

Because a dependent step only starts once its `preconditionStepId` has `COMPLETED`, a step that is permanently blocked (its guard never becomes true) also permanently blocks all of its dependents.

---

## Form execution status

| Status | Description |
|---|---|
| `PENDING` | Form created, waiting for user submission |
| `ASSIGNED` | Form claimed by a specific user |
| `COMPLETED` | User has submitted the form |
| `CANCELLED` | Form was cancelled (e.g. the process or step was cancelled) |

### Status transitions

```
PENDING → ASSIGNED → COMPLETED
        → COMPLETED
PENDING / ASSIGNED → CANCELLED
```
