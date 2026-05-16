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
| `CREATED` | Scheduled by the orchestrator, waiting for the next orchestration loop tick |
| `PENDING` | Task dispatched to worker, awaiting acknowledgement |
| `RUNNING` | Worker reported it has started processing |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout after retries exhausted |
| `TIMEOUT` | Step exceeded its configured `timeout` — will retry if retries remain |
| `CANCELLED` | Step was cancelled (e.g. process cancellation or saga compensation) |
| `SKIPPED` | `preconditionExpression` evaluated to `false` — step was skipped |

### Status transitions

```
CREATED → PENDING → RUNNING → COMPLETED
                             → ERROR
                   → TIMEOUT → PENDING (if retries remain)
                             → ERROR (if no retries remain)
          PENDING → CANCELLED
CREATED → SKIPPED (if preconditionExpression is false)
```

---

## Form execution status

| Status | Description |
|---|---|
| `Assigned` | Form created, waiting for user submission |
| `Completed` | User has submitted the form |

### Status transitions

```
Assigned → Completed
```
