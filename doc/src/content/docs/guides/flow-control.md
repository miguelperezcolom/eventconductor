---
title: Flow control
description: Per-key debounce, throttle and singleton on the starts of a workflow (startPolicy), per-topic rate limits on worker dispatch, and task priority — durable across pods, declared in the definition.
---

Three things a busy system needs and a workflow definition can now say for itself (2.33):

- **How starts are admitted, per key** — a `startPolicy` on an event trigger or on the definition:
  *debounce* a burst into one start, *throttle* to N starts a period (queue or drop the rest), keep a
  *singleton* per key (skip the new one, or replace the running one).
- **How fast a topic's tasks leave for their workers** — `workflow.workers.rate-limits.<topic>`: over the
  limit a task waits, its step `PENDING`, fairly across processes.
- **Which waiting task goes first** — a step's `priority` (0–100).

They are the engine's answer to Inngest's `debounce` / `throttle` / `singleton` and to Temporal's task-queue
priority — declared in the definition, validated at import and by the [Maven plugin](/reference/maven-plugin/)
with the same rules (`FlowControlRules`), and durable: the state lives in the engine's database and every pod
sees the same windows and queues.

## `startPolicy`

```yaml
triggers:
  - id: on-order-updated
    destination: orders
    type: com.acme.order.updated
    businessKey: "sync-${data.customerId}-${data.seq}"
    variables: { customerId: "${data.customerId}", seq: "${data.seq}" }
    startPolicy:
      key: "${data.customerId}"                               # template; absent = one key for the trigger
      debounce: PT30S                                         # start once, with the LAST event, after 30 s of quiet
      throttle: { limit: 10, period: PT1M, onLimit: queue }   # queue (default) | drop
      singleton: skip                                         # skip | replace
```

| Field | Meaning |
|---|---|
| `key` | A text template rendered for every start; the policy counts per rendered key. A trigger's own policy renders it over the **event** (`data`, `subject`, `key`… — the same context as its filter); a definition's policy over the start's **variables**, plus `businessKey` and `workflowId`. Absent: the whole scope is one key. A key that does not render, or renders blank, refuses the start (an `ERROR` receipt, a 400) — it is never silently shared. |
| `debounce` | ISO-8601 duration. The first start for a key opens a window; every further start in it **replaces** the pending one (its data and business key win) and pushes the window to now + debounce. When the window closes, the last start goes on. |
| `throttle` | `{limit, period, onLimit}`: at most `limit` starts per key per fixed window of `period` (opened by the first start). Over it, `queue` (default) keeps the start FIFO and releases it when a window has room; `drop` discards it. A new start never overtakes queued ones. |
| `singleton` | `skip`: while the last process the policy started for the key is still running, a new start is skipped. `replace`: the running one is cancelled (as an operator cancel: compensation runs) and the new one starts. |

At least one of `debounce`, `throttle` or `singleton` is required. Durations are positive ISO-8601 days to
seconds (`PT30S`, `PT1M`, `P1D`).

### Where it applies

| Declared on | Applies to |
|---|---|
| a trigger — in the definition's `triggers`, in an [`events.yaml`](/guides/domain-event-triggers/#eventsyaml-triggers-outside-the-definition), or one created from the UI, REST or MCP | the starts of that trigger; its keys are counted apart (scope `trigger:<workflow>/<trigger>`) |
| the definition (`startPolicy` at the top level) | `POST /workflow/api/processes`, [synchronous invocation](/guides/synchronous-invocation/), and the triggers that declare no policy of their own (scope `start:<workflow>`) |

Not under flow control: cron schedules (they have deterministic per-occurrence keys and
`maxConcurrentExecutions`), child processes of a `PROCESS` step (they belong to their parent), a raw
`ProcessCreationRequested` on the upstream topic, and the console's **New process** form (an operator's
deliberate start).

### Order and outcomes

`debounce` → `singleton` → `throttle` → start. A start released later — a debounce whose window closed, a
queued start whose window opened — re-enters at the next stage, so it honours the singleton and the throttle
**at the moment it actually starts**.

| Outcome | Event trigger receipt | `POST /workflow/api/processes` | Synchronous invocation |
|---|---|---|---|
| started | `STARTED` | 201, `EventConductor-Flow-Control: started` | the invocation goes on |
| replaced | `REPLACED` | 201, `EventConductor-Flow-Control: replaced` | the invocation goes on |
| skipped | `SKIPPED` | 200 with the **running** process, `EventConductor-Flow-Control: skipped` | 409 `SINGLETON_RUNNING` |
| debounced | `DEBOUNCED` | 202 `StartHeldBack {flowControl, businessKey, dueAt, detail}` | — (refused at validation) |
| throttled (queued) | `THROTTLED` | 202 `StartHeldBack` | 429 `THROTTLED` — a synchronous caller is never queued |
| dropped | `DROPPED` | 429 problem+json with `Retry-After` | 429 `THROTTLED` |

A receipt is written for every one of them, so a redelivered event that was debounced, throttled, dropped or
skipped is a duplicate like any other. A held-back REST start without a business key gets one
(`<workflow>-flow-<uuid>`) — the `businessKey` of the 202 is the key the process will have. With a debounce, the
process starts with the **last** start's data and business key: earlier callers' 202s name keys that never
come to exist.

A definition that is invocable synchronously (`syncInvocation`) cannot declare a definition-level `debounce`:
its caller waits for a process that would start later with somebody else's data. Put the debounce on a
trigger instead.

### Durability and concurrency

- **State** — `flow_control_key` (one row per scope and key: the throttle's window and count, the last start
  for the singleton) and `flow_control_pending` (the waiting starts: one `DEBOUNCE` row per key, any number of
  `THROTTLE` rows, ordered by a per-key sequence), migration V56. Memory mode keeps the same in the heap.
- **Across pods** — every decision for a key runs in one transaction that first takes `SELECT … FOR UPDATE` on
  the key's row, so two pods admitting for the same key take turns; different keys never wait for each other.
- **Release** — `FlowControlPass`, a scheduler pass every `workflow.flow-control.release-interval` (`PT1S`),
  starts due debounces and queued starts whose window has room; any pod may run it, each key under its lock.
- **Singleton and asynchronous creation** — a trigger start is created asynchronously (through the ingress),
  so for `workflow.flow-control.creation-grace` (`PT5M`) a start whose process is not visible yet still counts
  as running.

Guaranteed: no start is admitted past a throttle's limit in its window; one pending debounce per key; FIFO among
queued starts; one decision at a time per key. Not guaranteed: a crash between admitting and creating loses that
start (it is never doubled); a singleton is judged against the *last process started through the policy* — a
process started some other way for the same key is not counted; in a sharded fleet, keys are counted per shard
database.

Metric: `eventconductor.flow.starts{workflowDefinitionId, scope, outcome}` — `started`, `debounced`,
`throttled`, `dropped`, `skipped`, `replaced`, and `released` for a waiting start leaving its wait.

## Worker rate limits

```yaml
workflow:
  workers:
    rate-limits:
      partner-api: { permits: 50, period: PT1S }   # per topic
```

At most `permits` tasks of the topic are dispatched per `period` — to Kafka, to the [HTTP worker
queue](/guides/http-workers/) and to the embedded worker alike: the limit sits where every dispatch passes. The
topic is the step's `topic` (`downstream` for a step that names none; `http-calls`, `llm-calls` for the
built-in tasks).

Over the limit the task **waits**: its step stays `PENDING`, its log says so, and its timeout clock does not
run — a step's clock starts at dispatch, as it always has. The `RateLimitedDispatchPass`
(`workflow.workers.rate-limit-interval`, `PT0.2S`) dispatches waiting tasks as permits free up: **higher
priority first, then one task per process in turn**, oldest first — a process with a thousand items cannot
starve the others. A new task never jumps the queue of its topic.

Permits are a fixed window per topic in `rate_limit_window`, taken with a conditional `UPDATE … WHERE used <
permits` in the dispatch's own transaction (a dispatch that rolls back gives its permit back); waiting tasks are
rows of `deferred_dispatch`, claimed by exactly one pod. Metric:
`eventconductor.dispatch.rate_limited{topic, outcome}` (`deferred`, `released`).

## Task priority

```yaml
- id: charge-vip
  type: ACTION
  topic: payments
  priority: 90          # 0–100, higher first; default 50
```

On `ACTION`, `HTTP_CALL`, `LLM_CALL` and `RULE` steps. Honoured by:

- the **HTTP worker queue** — claimed `ORDER BY priority DESC, created_at`;
- the **embedded worker pool** when `workflow.embedded.worker-threads > 0` (a priority queue; inline dispatch
  has no queue to order);
- the **rate-limit queue** above.

**Kafka** keeps a topic's order: a partition is a log, and nothing reorders it. The priority travels on
`TaskExecutionRequested.priority` for a worker that wants to read it; the way to prioritise over Kafka is a
topic per priority class with its own consumers — or a rate limit on the low one.

## Testing

The [test kit](/guides/testing-workflows/)'s `advanceTime` stops at every moment a debounce window closes or a
throttle window opens, so flow control is tested on the test clock:

```java
runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C1", "seq", 1));
runtime.advanceTime(Duration.ofSeconds(10));
runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C1", "seq", 2));
runtime.advanceTime(Duration.ofSeconds(30));
assertThat(runtime.processes("order-sync")).singleElement()
        .satisfies(p -> assertThat(p).hasVariable("seq", "2"));
```

Rate limits too: `@EventConductorTest(properties = "workflow.workers.rate-limits.partner-api.permits=2")`,
then `advanceTime` one period at a time and count `invocationsOf(stepId)`. The windows are engine state shared by
the tests of one context — move the clock past a period at the start of each test.

## Compared with

| EventConductor | Inngest | Temporal |
|---|---|---|
| `startPolicy.debounce` | `debounce` | — (signal-with-start + timer in the workflow) |
| `startPolicy.throttle` (`queue`) | `throttle` | — |
| `startPolicy.throttle` (`drop`) | `rateLimit` | — |
| `startPolicy.singleton: skip / replace` | `singleton: skip / cancel` | `WorkflowIdReusePolicy` / `WorkflowIdConflictPolicy` (see [Long-running processes](/guides/long-running-processes/)) |
| `workflow.workers.rate-limits.<topic>` | `concurrency` / `throttle` on steps | task-queue rate limits (`maxTaskQueueActivitiesPerSecond`) |
| step `priority` | `priority.run` | task-queue priority |
| `processLock` (FIFO per key, [lock serialization](/reference/step-types/#lock)) | `concurrency` with `key` | — |
