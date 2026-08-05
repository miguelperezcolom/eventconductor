---
title: Process & Step Statuses
description: All status values for process instances and step executions.
---

## Process status

| Status | Description |
|---|---|
| `PENDING` | Created, not yet started — waiting for the orchestration loop |
| `RUNNING` | At least one step is currently executing |
| `PAUSED` | Held by an operator (or a paused definition) — no new steps start, clocks are frozen |
| `COMPLETED` | All steps finished successfully |
| `ERROR` | A step failed after exhausting all retries, and the process was left as it fell (no rollback, or nothing rollbackable had run) |
| `COMPENSATED` | The process failed and every executed rollbackable step was successfully compensated in reverse execution order (saga rollback) |
| `COMPENSATION_FAILED` | The process failed and a saga rollback started, but a compensation step itself failed after its own retries — the process is **partially rolled back** and needs manual resolution |
| `CANCELLED` | Process was cancelled by an operator or a cancellation event |

### Status transitions

```
PENDING → RUNNING → COMPLETED
                  → ERROR → COMPENSATED (saga rollback finished)
                          → COMPENSATION_FAILED (a compensation step itself failed)
         RUNNING → CANCELLED
PENDING → CANCELLED
PENDING / RUNNING → PAUSED → RUNNING (resume)
PAUSED → CANCELLED
```

A process in `ERROR` can be retried, which transitions it back to `RUNNING`.

`ERROR`, `COMPENSATED` and `COMPENSATION_FAILED` are all reached by failing, but they are three
different outcomes. `ERROR` means the process failed and was left as it fell. `COMPENSATED` means
it failed and then undid its side effects, in order, to the end. While the rollback is running the
process is `ERROR`; it flips to `COMPENSATED` only once the whole reverse-order compensation chain
has completed. If a compensation step itself fails after its own retries, the chain halts and the
process reaches the distinct, sticky `COMPENSATION_FAILED` — **not** left in `ERROR`, where a
half-rolled-back saga would be indistinguishable from a plain failure. It also increments
`eventconductor.compensations.failed` (the one compensation metric to alert on). All three are
sticky — nothing transitions them back to `RUNNING` on their own. See
[Retries, Timeouts & Compensation](/guides/retries-timeouts-compensation/).

**A `COMPENSATED` process is finished, and reads as finished.** Its badge is green, like
`COMPLETED`: the business outcome is not the happy one, but the process did exactly what it was
written to do. Its completion is 100% — the rollback ran to the end — and the steps the flow never
reached are `CANCELLED` rather than left `CREATED`, which used to leave a finished saga showing
steps that looked like they were waiting their turn. The step that failed keeps its `ERROR`: it is
the record of why any of this happened.

### Pause semantics

Pausing (`PauseProcessUseCase`, the UI **Pause** action, or the `pauseProcess` MCP tool)
freezes the frontier of the flow, not work already in flight:

- **In-flight work is still accepted.** Running workers finish and their reports are
  applied — steps complete and their output variables merge into the process. Messages
  still correlate and complete `WAIT_FOR_MESSAGE` steps. What is held is the *next* move:
  successors of completed steps do not start until the process is resumed.
- **Clocks freeze.** The timeout and TIMER schedulers skip paused processes, and on
  resume every non-terminal started step's `startedAt` is shifted forward by the pause
  duration — so step timeouts and TIMER due-moments resume exactly where they left off
  rather than firing in a burst. `Process.pausedAt` records when the pause began.
- **Blocking-error handling is deferred.** A failure that would normally block or fail
  the process only engages once the process is resumed.
- Cancelling a `PAUSED` process works as usual (`PAUSED → CANCELLED`).

A whole workflow definition can also be paused, which pauses all its `PENDING`/`RUNNING`
processes and makes new instances be created born-`PAUSED` — see
[Starting a Process — Pausing and resuming](/guides/starting-a-process/#pausing-and-resuming).

---

## Step execution status

| Status | Description |
|---|---|
| `CREATED` | Scheduled by the orchestrator, waiting for the next orchestration loop tick — or waiting for its preconditions to complete and its guard to become true |
| `PENDING` | Task dispatched to worker, awaiting acknowledgement |
| `RUNNING` | Worker reported it has started processing |
| `AWAITING_RETRY` | Failed with attempts remaining — waiting out its backoff delay before being re-dispatched. Carries a `deadlineAt` (when the backoff expires); the timeout scheduler wakes it |
| `COMPLETED` | Worker reported success |
| `ERROR` | Worker reported failure, or timeout, after retries exhausted |
| `TIMEOUT` | Step exceeded its configured `timeout` — will retry (via `AWAITING_RETRY`) if retries remain |
| `CANCELLED` | Step was never run or was stopped: process cancellation, saga compensation, or process completion while the step was still waiting |

### Status transitions

```
CREATED → PENDING → RUNNING → COMPLETED
                            → ERROR / TIMEOUT → AWAITING_RETRY (if attempts remain)
                                              → ERROR           (if no retries remain)
AWAITING_RETRY → CREATED (backoff elapsed — the scheduler re-dispatches it)
ERROR          → CREATED (manual process/step retry)
CREATED → CANCELLED (END step reached, or implicit completion, or process cancelled)
PENDING / RUNNING / AWAITING_RETRY → CANCELLED (process finished or cancelled)
```

**Auto-retry is asynchronous and backed off.** A failed step with attempts remaining is not
re-dispatched immediately — it is parked in `AWAITING_RETRY` for a delay that grows exponentially
per attempt (`workflow.retry.backoff-*`), so a worker that fails fast is never hammered in a tight
loop. The timeout scheduler wakes the step once the delay elapses and returns it to `CREATED`, from
where it is dispatched again. A **manual** process/step retry resets straight to `CREATED` (no
backoff).

### Preconditions: there is no "skipped" status

A step whose guard does not hold — one of its preconditions (`preconditionStepIds` / `preconditionStepId`) has not `COMPLETED`, or its `preconditionExpression` evaluates to a falsy value (or fails to evaluate; guards fail closed) — is **not** skipped. It simply never starts: it stays in `CREATED` and the guard is re-evaluated on every orchestration tick, so it can still run later if the guard becomes true. Steps are otherwise unordered: every `CREATED` step whose preconditions all hold and whose guard is truthy starts concurrently on the same tick (pure dataflow — array order and the deprecated `parallel` flag play no role). When the process finishes — an `END` step fires, or the process completes implicitly because no runnable steps remain — every step still in `CREATED`, `PENDING`, or `RUNNING` is transitioned to `CANCELLED`.

Because a dependent step only starts once **all** of its preconditions have `COMPLETED`, a step that is permanently blocked (its guard never becomes true) also permanently blocks all of its dependents.

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
