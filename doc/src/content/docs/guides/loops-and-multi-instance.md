---
title: Loops and Multi-Instance
description: Run a step once per item of a list (forEach), run a step again until a condition holds (repeat), and run a section of the flow again (loopBack) — declared in the definition, validated at import and at build.
---

A workflow's precondition graph is acyclic: a step waits for the steps before it, and a cycle would
mean steps waiting for each other forever — the validator still rejects one. Three step blocks say,
explicitly, that something runs more than once:

| Block | Says | Typical use |
|---|---|---|
| [`forEach`](#foreach--once-per-item) | run this step once per item of a list | reserve every room of a booking, call an API per order line, ask each approver |
| [`repeat`](#repeat--one-step-until-a-condition) | run this step again until a condition holds | poll a payment until it settles |
| [`loopBack`](#loopback--run-a-section-again) | run the steps from `to` to here again while a condition holds | draft → review → rework → draft… |

Every pass and every iteration is its own **step execution** with its own task id, so workers,
retries, timeouts, cancellation and compensation work exactly as they do for any step.

## forEach — once per item

```yaml
- id: reserve-room
  type: ACTION
  name: Reserve each room
  topic: booking
  preconditionStepId: start
  retries: 2                       # per iteration
  timeout: PT30S                   # per iteration
  compensable: true
  compensationStepId: release-room # once per completed iteration
  forEach:
    items: rooms                   # JEXL → a list
    as: room                       # default: item
    indexAs: roomIndex             # default: index
    mode: parallel                 # parallel (default) | sequential
    maxConcurrency: 3              # parallel only; default: no limit
    output: reservations           # collected into this variable, in item order
    outputElement: reservationId   # what each iteration contributes; default: all it reported
    completion: all                # all (default) | any | a JEXL condition
```

`forEach` is allowed on **ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL and RULE** steps.

**Items.** `items` is evaluated once, when the step starts, over the process variables. Variables
are strings in EventConductor, so here — and only here — a variable holding a JSON array or object
is read as one: `items: order.lines` works when `order` holds `{"lines": [...]}`. A literal works
too: `items: "['EUR', 'USD']"`. A missing list is an empty one.

**What an iteration sees.** The process variables, with `as` (the item) and `indexAs` (its 0-based
position) laid over them — for that iteration only. A worker receives the item as a variable:
text as is, objects and arrays as JSON. HTTP_CALL and LLM_CALL templates get it as structure, so
`url: "https://pms/rooms/${room.number}"` works. A PROCESS iteration starts one child process per
item, each with the item among its variables.

**What an iteration reports is not written to the process.** Parallel iterations would race on the
same variables. It is kept on the iteration, and `output` collects it when the step completes: a
JSON array with **one element per item, in item order** — `null` for an iteration that did not
complete. Each element is `outputElement` evaluated over what that iteration reported (plus `as` and
`indexAs`), or, without one, an object of everything it reported.

**Completion.**

- `all` — the step completes when every iteration has.
- `any` — the first completed iteration completes the step; the others are cancelled.
- a JEXL condition — checked after each completed iteration, over the process variables, the
  `output` gathered so far (under its own name) and `completedIterations`, `totalIterations`,
  `activeIterations`. When it holds the step completes and the rest are cancelled:
  `completion: "completedIterations >= 2"`.

**Failure.** Each iteration has the step's own `retries`. One that fails for good (or times out
for good) fails the step: the iterations not yet started are cancelled, the ones at a worker are sent
a cancellation, and the step ends ERROR (TIMEOUT for a timeout, so `onTimeoutStepId` routes it).
Retrying the failed step from the UI (or `RetryStepExecution` on any of its iterations) re-runs
**only the failed and cancelled iterations**, each with a fresh retry budget; the completed ones stand.

**Compensation.** The unit of compensation is the completed iteration. When the saga rolls back —
because a later step failed, or because the forEach itself failed after some iterations completed —
the compensation step runs **once per completed iteration**, newest first, one at a time, each
seeing that iteration's item and index **and everything it reported**:

```yaml
- id: release-room
  type: ACTION
  name: Release a room
  topic: booking                   # receives room, roomIndex and reservationId
```

**Empty list, limits.** An empty list completes the step at once, with `output` `[]`. A list longer
than `workflow.foreach.max-items` (default 1000) fails the step at start.

**Timeouts and cancellation.** `timeout` and `deadline` apply to each iteration. Cancelling the
process cancels every iteration in flight; an END reached on another branch cancels the forEach and
its iterations.

## repeat — one step until a condition

```yaml
- id: poll-payment
  type: HTTP_CALL
  name: Poll the payment
  preconditionStepId: charge
  http:
    connection: payments
    path: "/payments/${paymentId}"
    output: { paymentStatus: "body.status" }
  repeat:
    until: "paymentStatus == 'SETTLED'"
    maxIterations: 20
    delay: PT30S
```

Each time the step completes, `until` is evaluated **once**, over the process variables — with what
the run just reported already written — plus `iteration`, the 1-based number of that run. True: the
flow moves on. False: the step runs again, as a new execution, after `delay` (milliseconds or an
ISO-8601 duration; absent = at once). The step's `retries` are per run: a run that fails is retried;
a run that completes with the condition still false is repeated.

`repeat` is allowed on the same step types as `forEach`, and a step declares one or the other.

## loopBack — run a section again

```yaml
- id: draft
  type: USER_TASK
  formId: draft-form
  preconditionStepId: start
- id: review
  type: USER_TASK
  formId: review-form
  preconditionStepId: draft
- id: decide
  type: CHOICE
  name: Approved?
  preconditionStepId: review
- id: rework
  type: ACTION
  name: Ask for rework
  topic: notifications
  preconditions:
    - { stepId: decide, expression: "verdict == 'REWORK'" }
  loopBack:
    to: draft             # must be upstream of this step
    maxIterations: 5      # the first pass included
- id: publish
  type: ACTION
  name: Publish
  topic: cms
  preconditions:
    - { stepId: decide }  # the CHOICE's default branch
```

When the step carrying `loopBack` completes and `while` holds (absent = always), the **section** —
every step on a path from `to` to this step, both included — runs again:

- each step of the section gets a **fresh execution**, so JOIN barriers, XOR joins and CHOICE latches
  inside it start over, and a CHOICE picks afresh on every pass;
- steps outside the section keep what they did, and **process variables carry over**;
- anything still in flight inside the section is cancelled;
- `to` waits `delay` before it runs again, when one is given.

Put the `loopBack` on a CHOICE branch, as above, to make the CHOICE the condition; or put it on any
step with a `while`:

```yaml
loopBack: { to: fork, while: "again == 'yes'", maxIterations: 3 }
```

`while` sees the process variables plus `iteration`, the 1-based number of the pass that just ended.

### Rules

Checked when a definition is imported and by the Maven plugin's `validate` goal, with the same code:

- `to` must exist, must not be the step itself (use `repeat`), and must be **upstream** of it;
- not on a START, END or REPLY; the section may not contain a REPLY (a process replies at most once)
  or an END;
- two loops must be **nested or apart** — sections that share steps without one containing the other
  are rejected;
- a compensation step cannot carry `forEach`, `repeat` or `loopBack`.

## Caps

A loop that would run past a cap **fails the step** — finally, without spending its retries — and the
process's failure handling (compensation, ERROR) takes over:

| Cap | Where |
|---|---|
| `maxIterations` | on the `repeat` / `loopBack` block — required |
| `maxSuccessfulExecutions` | on any step: how many times it may complete in one process; enforced when a loop would run it again |
| `defaultMaxStepExecutions` | on the workflow: the default for every step without its own |
| `workflow.loops.max-iterations` | engine configuration (default 1000): bounds every loop whatever it declares |

## Seeing it run

The process **Steps** tab lists every pass and every iteration (`reserve-room · item 2`,
`poll-payment · run 3`). On the diagram, a forEach step is drawn as a stack of cards with what it runs
over (`∥ rooms`, `⇣ lines` for sequential), a repeating step carries a `↻ until …` chip, and a
`loopBack` is a dashed violet back-edge with its condition. While a process runs, a forEach node
shows `completed/total` iterations and a looped node how many times it ran (`×3`).

In storage, a pass is a step execution with `iteration` > 0 (the one it replaced `superseded`), and an
iteration is one with a `parentExecutionId` (its forEach) and an `itemIndex`. Analytics count every
one of them as a run of its step.

## Configuration

| Property | Default | |
|---|---|---|
| `workflow.foreach.max-items` | `1000` | the longest list a forEach may run over |
| `workflow.loops.max-iterations` | `1000` | the most runs any loop may make |

## Not yet

- a deadline for the whole forEach (today `timeout`/`deadline` bound each iteration);
- tolerating failed iterations (`onIterationError: continue`);
- `forEach` on SEND_MESSAGE, PUBLISH_EVENT, WAIT_FOR_MESSAGE, or on a step with `awaitReply`.
