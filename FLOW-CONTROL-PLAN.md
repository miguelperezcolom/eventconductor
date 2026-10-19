# Plan: per-key flow control and long-lived processes

> Status: **DECIDED (2026-10-19)** — implementation on `feat/flow-control-long-lived` (2.33).
> Parity target: Inngest's `concurrency` / `throttle` / `debounce` / `singleton`, Temporal's task-queue
> priority, Continue-As-New and `WorkflowIdReusePolicy` — declared in the definition (DSL-first),
> validated at import and in the Maven plugin by the same `definition-analysis` rules.

## 1. What exists to build on

| Piece | Where | What we reuse |
|---|---|---|
| Starts from events | `InboundEventService.start` → `IngressRouter.route` | the door where a trigger start is decided; receipts record the outcome |
| Starts from callers | `ProcessOperationsService.start` (REST), `SyncInvocationService.start` | synchronous doors: the caller is told the outcome |
| Creation | `CreateProcessUseCase` | the chokepoint every start path ends in — the business-key rule lives here |
| Definition-level caps | `maxConcurrentExecutions` / `enqueueOnLimit`, `processLock` (FIFO per key) | unchanged; flow control is per **start key**, they are per definition / per lock key |
| Dispatch | `StartStepExecutionUseCase.dispatch` → `DownstreamEventPublisher` (Kafka, HTTP queue, embedded) | the one chokepoint for worker rate limits |
| Time | `EngineClock`, `SchedulerPass`, the test kit's `advanceTime` | every new timer is a `SchedulerPass` that also says when it is next due |

## 2. Flow control on starts — `startPolicy`

```yaml
# on a trigger (definition triggers, events.yaml, triggers created from the UI/REST/MCP)
triggers:
  - id: on-order-updated
    destination: orders
    type: com.acme.order.updated
    businessKey: "order-${data.orderId}"
    startPolicy:
      key: "${data.customerId}"                          # template over the event; absent = one key for the trigger
      debounce: PT30S                                    # start once, with the LAST event's data, after 30 s of quiet
      throttle: { limit: 10, period: PT1M, onLimit: queue }   # queue (default) | drop
      singleton: skip                                    # skip | replace

# on the definition: applies to REST starts, sync invocations, and triggers without their own
startPolicy:
  key: "${customerId}"                                   # template over the start's variables (+ businessKey, workflowId)
  throttle: { limit: 100, period: PT1M, onLimit: drop }
  singleton: replace
```

**Scope** — a policy is counted per *scope* and *key*: `trigger:<workflow>/<trigger>` for a trigger's own
policy, `start:<workflow>` for the definition's. The key is the rendered `key` template (absent → the
empty key: the whole scope is one key). A key that does not render, or renders blank, is an `ERROR`
(trigger receipt / 400) — never a silently shared key.

**Order of evaluation** — `debounce` → `singleton` → `throttle` → start. Each queued start (debounced,
throttled) re-enters at the next stage when it is released, so a released start still honours the
singleton and the throttle *at the moment it starts*.

| Feature | Semantics |
|---|---|
| `debounce: PT30S` | the first start for a key opens a window; every further start for the key in the window **replaces** the pending one (latest data wins) and pushes the window to now + 30 s. When the window closes the last start goes on (singleton, throttle). Outcome `DEBOUNCED`. |
| `throttle: {limit, period, onLimit}` | at most `limit` starts per key per fixed window of `period` (the window opens with the first start). Over it: `queue` keeps the start FIFO and releases it when a window has room (outcome `THROTTLED`); `drop` discards it (`DROPPED`). A queued start never overtakes an earlier one. |
| `singleton: skip` | if the last process started for the key is still running (not terminal), the start is skipped (`SKIPPED`; REST answers 200 with the running process). |
| `singleton: replace` | the running one is cancelled (`CancelProcessUseCase`, compensation as for any cancel) and the new one starts (`REPLACED`). |

**Doors**

| Door | Policy | Outcome reporting |
|---|---|---|
| Event trigger | the trigger's, else the definition's | receipt outcome `STARTED`, `DEBOUNCED`, `THROTTLED`, `DROPPED`, `SKIPPED`, `REPLACED`; metric |
| REST `POST /processes` | the definition's | 201 started/replaced; 200 + running process for skipped (and for an existing business key, as today); 202 `{flowControl: debounced|throttled, dueAt}`; 429 + `Retry-After` for dropped |
| Sync invocation | the definition's | `singleton: skip` → 409 `SINGLETON_RUNNING`; throttled (either `onLimit`) → 429 `THROTTLED` + `Retry-After` (a synchronous caller cannot be queued); `debounce` with `syncInvocation` is refused by validation |
| Cron, PROCESS-step children, raw upstream `ProcessCreationRequested`, the console's *New process* form | none | cron has its own per-occurrence keys and `maxConcurrentExecutions`; children belong to their parent; the console is an operator acting by hand (the MCP has no start tool) |

**Durability and concurrency** — two tables (V56):

- `flow_control_key (id = sha256(scope, key), scope, flow_key, window_start, window_count, last_process_id, last_business_key, updated_at)` — the admission state of one key;
- `flow_control_pending (id, key_id, kind DEBOUNCE|THROTTLE, workflow_id, request_json, due_at, enqueued_at, absorbed)` — the starts waiting for their moment (one DEBOUNCE row per key, any number of THROTTLE rows).

Every admission and every release runs in one short transaction that first takes
`SELECT … FOR UPDATE` on the key's row (inserted beforehand, idempotently, in a transaction of its own), so
two engines (pods) admitting for the same key are serialized by the database; different keys never wait
for each other. A `FlowControlPass` (`SchedulerPass`, every second by default) releases due debounces and
throttled starts whose window opened, key by key under the same lock; any pod may run it. Memory mode:
the same logic over maps, a lock per key. The test kit stops `advanceTime` at the next due moment
(`SchedulerPass.nextDueAt()`).

What is guaranteed: no start is admitted past a throttle's limit for its window, one pending debounce per
key, FIFO among queued starts, at most one *start decision* at a time per key. What is not: the window
between admission and creation (a crash right after admitting loses that start — it is never doubled);
singleton is judged against the *last process started through the policy* (an operator-started process
without the policy is not counted). Sharded fleets count per shard database.

**Metrics** — `eventconductor.flow.starts{workflow, scope, outcome}` (started, debounced, throttled,
dropped, skipped, replaced, released), `eventconductor.flow.pending{kind}` gauge.

## 3. Worker rate limits and task priority

```yaml
workflow:
  workers:
    rate-limits:
      payments-api: { permits: 50, period: PT1S }      # per topic; a step's topic (or downstream / http-calls / llm-calls)
```

Applied in `StartStepExecutionUseCase.dispatch`, the single point every dispatch passes (Kafka, HTTP queue,
embedded). Over the limit the task is **deferred**: the step stays `PENDING`, its timeout clock does not
run (it starts at dispatch, as today), and a row goes to `deferred_dispatch (step_execution_id, process_id,
topic, priority, deferred_at)`. A `RateLimitedDispatchPass` releases deferred tasks as permits free up:
**priority first, then round-robin across processes** (fairness: one task per process per round, oldest
first), so one process with a thousand items cannot starve the others. Permits are a fixed window per topic in
`rate_limit_window (topic, window_start, used)`, taken with a conditional `UPDATE … WHERE used < permits`
— atomic across pods. A new dispatch never jumps a non-empty deferred queue of its topic.

`priority: 0–100` (default 50) on ACTION and HTTP_CALL (and the other worker steps) travels on
`TaskExecutionRequested.priority`. Honoured by: the deferred queue; the HTTP worker queue (`http_task.priority`,
claimed `ORDER BY priority DESC, created_at`); the embedded pool when `workflow.embedded.worker-threads > 0`
(a priority queue; inline dispatch has no queue to order). **Kafka**: a topic is a log — order is the topic's;
priority is carried on the record for workers that want it, and the documented way to prioritise is a topic
per priority class with its own consumers (or a rate limit on the low one).

## 4. Long-lived processes

### 4.1 `CONTINUE_AS_NEW`

```yaml
- id: roll-over
  type: CONTINUE_AS_NEW
  name: Next month
  preconditionStepId: wait-month
  continueAsNew:
    version: latest           # same (default: the definition snapshot this process runs) | latest
    variables:                # absent: carry every variable; a list: carry these; a map: name → template
      customerId: "${customerId}"
      month: "${month + 1}"
```

Resolved by the step-over like REPLY (no worker): the variables are computed, the new process is created
**in the same transaction** — same definition (snapshot or latest), same business key, `continuedFrom` =
this process — and this process is completed with `continuedAs` = the new one: status `COMPLETED`, outcome
`continued`; other in-flight branches are cancelled as an END does. A child process that continues keeps its
`parentStepExecutionId`: the parent is notified only when the *last* run of the chain ends.

The links are on `process_entity (continued_from_process_id, continued_as_process_id)`, shown in the console,
REST (`continuedFrom`, `continuedAs`, `outcome`) and MCP. Retention purges each finished run on its own
clock; a purged predecessor leaves a dangling `continuedFrom` id, which the views show as "purged". With
loops (#543) this caps history: a monthly subscription is a loop of N months, then `CONTINUE_AS_NEW`.

### 4.2 `businessKeyPolicy`

```yaml
businessKeyPolicy: unique            # unique (default) | allowAfterFinished | allowAfterFailed | terminateRunning
```

| Policy | Existing process (same definition) holds the key and is… | |
|---|---|---|
| `unique` | anything | the start collapses into it (today) |
| `allowAfterFinished` | terminal (COMPLETED, ERROR, CANCELLED, COMPENSATED, COMPENSATION_FAILED) | a new run takes the key |
| `allowAfterFailed` | ERROR, CANCELLED, COMPENSATED, COMPENSATION_FAILED (not COMPLETED) | a new run takes the key |
| `terminateRunning` | running → cancelled, then a new run; terminal → a new run | |

A key held by **another** definition's process is never reused (409 / ERROR receipt, as today). The policy
is applied in `CreateProcessUseCase`, so every start path honours it (REST, console, sync, triggers,
cron, upstream records); CONTINUE_AS_NEW always reuses its own key.

**Storage** — `process_entity.business_key` **keeps its UNIQUE constraint** and means "the run that holds the key
now". A run that hands its key over (CONTINUE_AS_NEW, a reuse policy) moves it to `released_business_key` (V56) in
the same transaction, written (flushed) before the next run is inserted, through the run's optimistic version — so
two takeovers of one key cannot both win. `findByBusinessKey` answers the holder; the listing and MCP
`listRunsOfBusinessKey` match either column.

*Rejected:* dropping the UNIQUE constraint and moving uniqueness to a `business_key_holder` table. It broke the
rolling upgrade: a 2.32 pod relies on the constraint to fail a duplicate creation (and its `findByBusinessKey`
expects one row per key), so during a mixed-version rollout an old pod could create a second process for a key.
Keeping the constraint keeps both versions correct side by side (tested on PostgreSQL: an old-style insert racing
a new-style hand-over never leaves two running processes with the key).

**Large tables** — V56 adds only tables and nullable / constant-default columns (no rewrite on PostgreSQL 11+); the
two indexes on existing tables are built `CONCURRENTLY` on PostgreSQL by V56.1, a non-transactional Java migration.

**Interplay** — *idempotent REST starts* (200 for an existing key) hold while the existing run is not
reusable; once the policy lets the key be reused, the same request starts a new run (a client that
retries blindly after the first run ended gets a second run — use `unique`, or sync invocation's
idempotency key). *Event triggers*: the default business key is per event, so a reuse policy only
matters with a `businessKey` template; redeliveries are still collapsed by the receipts. *Sync
invocation*: the idempotency key still answers the same invocation; `BUSINESS_KEY_TAKEN` is raised only
when the policy refuses.

## 5. Validation (engine + Maven plugin, `definition-analysis`)

`StartPolicyRules` (key template; ISO-8601 positive durations; limit ≥ 1; onLimit/singleton enums;
at least one of debounce/throttle/singleton; debounce + `syncInvocation` on the definition refused),
`BusinessKeyPolicyRules`, `ContinueAsNewRules` (version enum, variables list/map, no outgoing links:
it ends the process, not inside forEach/repeat), `priority` 0–100. Keys and variable templates computed
from sensitive variables are refused like lock keys once sensitive variables (#557) land.

## 6. Out of scope

Concurrency limits per key on *running* processes beyond singleton (use `processLock`); batching of
events into one start (Inngest `batchEvents`); per-tenant fairness across topics; priority for Kafka beyond
topic-per-class; flow control on cron starts.
