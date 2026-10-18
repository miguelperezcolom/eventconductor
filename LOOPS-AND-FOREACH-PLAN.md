# Plan: loops and multi-instance (`forEach`, `repeat`, `loopBack`)

> Status: **DECIDED (2026-10-18)** — implementation on `feat/loops-foreach`.
> Builds on the CHOICE latching of `WorkflowOrchestrationService`, the saga rollback of
> `CompensationService` and the retry park/release of `AutoRetryService` / `CheckRetryUseCase`.

## 1. Goal

Three things a workflow could not say until now, all declared in the definition and validated at
import and in the Maven plugin:

1. **Run a step once per item of a list** — reserve every room of a booking, call an API for each
   order line, ask each approver. Today that needs a DYNAMIC step and a worker that injects steps.
2. **Run a step again until a condition holds** — the polling pattern: call the payment provider
   every 30 s until the payment is settled, at most 20 times.
3. **Run a section again** — a back-edge: "ask for corrections, review again; loop while the
   review says *rework*", without making the precondition graph cyclic.

Before this, the validator rejected every precondition cycle (`WorkflowDefinition.checkInvariants`,
mirrored in `SpecValidator`), the engine ran every step once per process, and
`maxSuccessfulExecutions` / `defaultMaxStepExecutions` were schema-validated metadata waiting for
"step re-execution" to land. This is that.

## 2. The DSL

### 2.1 `forEach` — multi-instance

```yaml
- id: reserve-room
  type: ACTION
  name: Reserve each room
  preconditionStepId: start
  retries: 2
  timeout: PT30S                       # per iteration
  compensable: true
  compensationStepId: release-room     # runs once per COMPLETED iteration, in reverse order
  forEach:
    items: rooms                       # JEXL → list; a variable holding a JSON array/object is read as one
    as: room                           # default: item
    indexAs: roomIndex                 # default: index
    mode: parallel                     # parallel (default) | sequential
    maxConcurrency: 3                  # parallel only; default: no limit
    output: reservations               # JSON array, one element per item, in item order
    outputElement: reservationId       # JEXL over what the iteration reported (+ room, roomIndex); default: all of it, as an object
    completion: all                    # all (default) | any | a JEXL condition
```

Allowed on **ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL, RULE** — the types that do work. Not on
control-flow nodes (START, END, FORK, JOIN, CHOICE), not on TIMER/WAIT_FOR_MESSAGE/SEND_MESSAGE/
PUBLISH_EVENT/LOCK/UNLOCK/REPLY/DYNAMIC (no per-item meaning, or one that needs its own design —
see §9).

### 2.2 `repeat` — one step until a condition

```yaml
- id: poll-payment
  type: HTTP_CALL
  name: Poll the payment
  preconditionStepId: start
  http: { connection: payments, path: "/payments/${paymentId}", output: { paymentStatus: "body.status" } }
  repeat:
    until: "paymentStatus == 'SETTLED'"   # JEXL; sees the variables the run just reported, plus `iteration`
    maxIterations: 20                     # required; the first run included
    delay: PT30S                          # optional; wait before each new run
```

Same step types as `forEach`; a step declares at most one of `forEach` and `repeat`.

### 2.3 `loopBack` — an explicit back-edge

```yaml
- id: review
  type: USER_TASK
  formId: review-form
  preconditionStepId: draft
  loopBack:
    to: draft                      # must be upstream of this step
    while: "verdict == 'REWORK'"   # optional: absent = always (a CHOICE branch carrying it is then the condition)
    maxIterations: 5               # required; the first pass included
    delay: PT0S                    # optional
```

`while` is a reserved word in Java, so the record component is `condition` with
`@JsonProperty("while")`; the DSL, the schema and the docs all say `while`.

A loop "with a CHOICE" is written by putting the `loopBack` on a branch of the CHOICE (unconditional),
or on the CHOICE itself with a `while`. Both are tested.

## 3. Decisions

| # | Decision |
|---|---|
| A | **Three blocks, one per intent**: `forEach`, `repeat`, `loopBack`, each a nested object like `http:`/`llm:`. Nothing about them is a precondition: the precondition graph stays acyclic and an ordinary cycle stays an error. |
| B | **Iterations are step executions.** A forEach step's execution becomes a *container*; each item gets a *child* execution (`parentExecutionId`, `itemIndex`) with its own id — task ids stay unique per iteration, so workers, cancellations and late-reply fencing work unchanged. A loop pass is a **new execution of the same step** (`iteration` = 0, 1, 2…); the one it replaces is marked `superseded` and kept as history. |
| C | **The orchestrator reasons over the *current* view**: top-level, non-superseded executions. Every rule it had (JOIN barrier, XOR join, CHOICE latch, END, implicit completion, held-by-guard) is evaluated on that view, so re-opening a section *is* resetting its latches — no per-rule special cases. Children are driven by the container pass (§4.1). |
| D | **Per-iteration scope without clobbering**: an iteration sees the process variables with `as`/`indexAs` laid over them (its `scopeJson`). HTTP_CALL/LLM_CALL templates get the item *typed* (`${room.number}` works); workers get it as a string (JSON for objects). **Nothing an iteration reports is written to the process** — it is kept on the iteration (`outputJson`) and only `output` collects it, in item order, when the container completes. Parallel iterations therefore cannot race on process variables. |
| E | **Loop decisions latch.** `repeat.until` / `loopBack.while` is evaluated once, on the first step-over after the step completes, and the outcome is recorded on the execution (`loopDecision` = `EXITED` / `LOOPED` / `EXHAUSTED`). A variable changing later never re-opens a loop that already exited (same reasoning as the CHOICE latch). |
| F | **Caps fail loud.** A loop that would exceed its `maxIterations`, the step's `maxSuccessfulExecutions` (or the workflow's `defaultMaxStepExecutions`), or the engine's hard cap `workflow.loops.max-iterations` (default 1000) fails the step — final (`nonRetryable`), so the failure pipeline (compensation, process ERROR) engages. A forEach whose list is longer than `workflow.foreach.max-items` (default 1000) fails at start. `maxSuccessfulExecutions`/`defaultMaxStepExecutions` are now **enforced**. |
| G | **Failure**: one iteration failing for good (its own `retries` spent) fails the container: the iterations still to start are cancelled, those in flight are cancelled (`TaskCancellationRequested`), and the container ends ERROR (non-retryable — the iteration already spent the retries; retrying the container would multiply them). A TIMEOUT iteration ends the container TIMEOUT, so the step's `onTimeoutStepId` routes it. |
| H | **Compensation of completed iterations / passes**: the unit of compensation is a completed *iteration* (forEach) or a completed *pass* (loop). A compensable step with one unit compensates exactly as before (one compensation execution). With several, the compensation execution runs as a container over the units — sequential, newest first — each seeing the unit's item/index **and what that unit reported** (e.g. the `reservationId` to release). Completed iterations of a container that failed are compensated too: they did commit work. |
| I | **Timeouts** are per iteration (the step's `timeout`/`deadline` on each child); the container has no deadline of its own. A whole-forEach timeout is deferred (§9). |
| J | **Cancellation**: cancelling the process cancels every live execution including iterations (one `TaskCancellationRequested` per in-flight iteration). A container cancelled by an END on another branch cascades to its children in the same step-over. |
| K | **Empty list** completes the container at once, `output` = `[]`. |
| L | **Operator retry of a failed forEach** resumes it: completed iterations are kept, failed/cancelled ones run again. Restarting the process from the beginning discards all iterations and passes. |
| M | **Process status & percentage** count the current view only; history and iterations do not skew them (a superseded pass that failed is not a process failure — it was re-run). |
| N | **Analytics count runs**: every execution — pass, iteration, and the forEach container itself — is a row of its step id, which is what "how often does `poll-payment` run" reads (a forEach of 3 items shows 4 executions of its step: the container and 3 iterations; refining that is deferred). The stalled-step count leaves containers out (they owe nothing and have no deadline by design); an iteration in flight keeps its process "in flight" for the stalled-process report. |
| O | **Sync REPLY**: a REPLY may not sit inside a loop section nor carry `repeat`; REPLY cannot carry `forEach` (not a repeatable type). So "replies at most once" stays a static guarantee. |
| P | **Worker contracts unchanged**: `TaskExecutionRequested` keeps its shape; the item and index travel as ordinary variables. No shared DTO gains a required field. |
| Q | **Sharding / Kafka**: iterations and passes belong to their process like any execution — same partition key (process id), same outbox, same optimistic-lock fence. Nothing new crosses processes. |

## 4. Engine

### 4.1 The container pass (forEach)

`WorkflowOrchestrationService.calculateNextTransitions` gains a first pass, before the blocking-error
check, over every container that is in flight (PENDING) and its non-superseded children:

1. A child failed for good (ERROR, or TIMEOUT) → cancel the rest, container ERROR/TIMEOUT (logged with
   the item index).
2. Completion reached (`all`: every child COMPLETED; `any`: one COMPLETED; condition: true after a
   completion) → cancel what is left, write `output`, container COMPLETED.
3. Otherwise start CREATED children in index order while in-flight children < `concurrency()`
   (sequential = 1). In-flight = PENDING/RUNNING/AWAITING_RETRY/WAITING_ON_LOCK.
4. A container that reached CANCELLED any other way (END elsewhere, cancel) cascades to its children.

The container itself is started like any step (eligibility unchanged); `StepExecution.start` for a
container evaluates `items` once, materialises the children (`StepExecution.iterationOf`) and stays
PENDING with no task dispatched. In a process that is ERROR (another branch failed) containers stop
launching iterations, their in-flight ones finish, and the container ends CANCELLED; PAUSED holds
them like everything else.

### 4.2 The loop pass (repeat / loopBack)

Also before eligibility: every current execution that is COMPLETED, has `repeat` or `loopBack` and no
`loopDecision` yet is decided:

- **repeat**: `until` true → `EXITED`. False → if the cap allows, `LOOPED`: the execution is
  superseded and a new one (`iteration + 1`) created; with a `delay` it is parked AWAITING_RETRY with
  its wake in `deadlineAt` (the retry scheduler releases it — no new scheduler).
- **loopBack**: `while` false → `EXITED`. True → if every section step is within its caps, `LOOPED`:
  each current execution of the section is superseded (in-flight ones CANCELLED with a cancellation
  sent to the worker, never-reached ones CANCELLED silently) and replaced by a fresh CREATED one
  (`iteration + 1` per step). `to` gets the `delay`.
- Cap exceeded → `EXHAUSTED`, the step flips to ERROR (final) with a log line saying which cap.

The **section** is computed by `LoopRules.section(to, from, …)` from definition-analysis — the same
code the validator uses — over the step JSON frozen on the executions.

### 4.3 Worker reports for iterations

`UpdateStepExecutionUseCase`: a report on a child is recorded on the child (`recordOutput`) instead of
`process.updateVariables`. PROCESS iterations go through the same path (the child process's
`outputVariables` arrive as the report). Everything else — attempt fencing, terminal guard, retries —
is unchanged and per iteration.

### 4.4 Everything else that reads executions

A small `StepExecutions` helper (`current`, `childrenOf`, `isContainer`) is used by: process status
recompute (`ProcessUpdateStepExecutionUpdateUseCase`), `CompensationService`, `InjectStepsUseCase`
(budget counts current top-level executions), restart/retry, the process view, the stalled report.

## 5. Storage

`StepExecution` gains seven fields; Flyway migration **V47** (the next free number on `main`; portable
DDL like V39–V46, H2 + PostgreSQL), mirrored in `StepExecutionEntity` and its mapper:

| column | type | meaning |
|---|---|---|
| `iteration` | `INT NOT NULL DEFAULT 0` | loop pass of this execution |
| `superseded` | `BOOLEAN NOT NULL DEFAULT FALSE` | replaced by a later pass, or an iteration discarded by a restart |
| `loop_decision` | `VARCHAR(16)` | latched outcome of `until`/`while` |
| `parent_execution_id` | `VARCHAR(255)` | the container of a forEach iteration (indexed) |
| `item_index` | `INT` | the iteration's position |
| `scope_json` | `CLOB/TEXT` | the variables laid over the process for this iteration |
| `output_json` | `CLOB/TEXT` | iteration: what it reported; container: the collected `output` |

Existing rows read as iteration 0, not superseded, no parent — exactly what they are.

## 6. Validation (shared: `definition-analysis` `LoopRules`)

Called by `WorkflowDefinition.checkInvariants` (after the cycle check) and by the Maven plugin's
`SpecValidator`; JEXL in `items`, `outputElement`, `completion`, `until`, `while` is parsed on both
sides. Errors:

- forEach/repeat on a type that cannot carry it; both on one step; on a compensation step.
- forEach without `items`; bad `mode`; `maxConcurrency` < 1 or with `sequential`; `as`/`indexAs`/`output`
  not identifiers; `as` == `indexAs`; `outputElement` without `output`.
- repeat without `until`; repeat/loopBack without `maxIterations` ≥ 1; negative or malformed `delay`.
- loopBack on START/END/REPLY; `to` missing, unknown, the step itself (use `repeat`), or not upstream.
- a section containing a REPLY or an END; a REPLY with `repeat`.
- two loops whose sections cross (share steps, neither contains the other).
- `forEach` on an HTTP_CALL/PUBLISH_EVENT with `awaitReply` (every iteration would correlate on the same key; deferred — a PROCESS per item does it today). `repeat` + `awaitReply` is allowed: each pass is its own request and wait.

Warning: a repeat nested in a loop section (multiplied bound).

## 7. UI, editor and plugins

- **Process detail**: the Steps tab lists every pass and iteration ("reserve-room · item 2", "poll-payment ·
  run 3"); the diagram's overlay is built from the current execution per step and carries
  `iterations: {total, completed, failed, active}` for a container and `runs` for a looped step; the node
  shows them as a badge.
- **Graph editor** (`frontend/src/eventconductor-workflow-graph.ts`, bundle rebuilt with `npm run build`):
  a forEach step is drawn as a stacked card with a `⫴ items` badge (∥ parallel / ⇣ sequential); a
  `repeat` step gets a ↻ self-loop chip with its `until`; a `loopBack` is a dashed back-edge from the
  step to its target with a chip showing `while` and `max N`. The properties panel edits `forEach`,
  `repeat` and `loopBack` (items/as/indexAs/mode/maxConcurrency/output/completion; until/maxIterations/
  delay; to/while/maxIterations/delay).
- **IDE plugins** take the schema and the bundle from the engine at build time (VS Code
  `scripts/sync-assets.js`, IntelliJ `syncSchema`/`syncBundle`); no plugin code or version changes.

## 8. Tests

Unit: `LoopRulesTest` (definition-analysis), orchestrator/container/loop unit tests, compensation unit
tests, validator + `SpecValidatorTest` cases. E2e (memory and JPA): parallel and sequential forEach,
output order, empty list, iteration failure with retries and compensation of the completed ones,
cancellation mid-iteration, maxConcurrency, completion any/condition, repeat-until with delay,
loopBack with and without CHOICE, iteration caps (repeat, loopBack, maxSuccessfulExecutions, items cap).
Dist (Kafka) e2e for a parallel forEach. Browser suite: the editor renders the stacked badge and the
back-edge.

## 9. Deferred

- A deadline for the whole forEach (today: per iteration).
- `onIterationError: continue` (tolerate failed iterations and collect nulls).
- forEach on SEND_MESSAGE / PUBLISH_EVENT / WAIT_FOR_MESSAGE, and on steps with `awaitReply`.
- Nested forEach inside one step (a PROCESS iteration whose child has its own forEach already works).
- A dedicated REST/MCP shape for iterations (they appear as step executions with the new fields).
