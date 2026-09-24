---
title: Synchronous Invocation
description: Start a durable process over HTTP, wait up to a deadline, and receive the reply the process itself emits — with idempotent retries, a failure contract for sagas, and a progress stream.
---

A client can start a process over HTTP, **wait** for a bounded time, and receive a **reply the process emits from its own state** — typically a saga with compensation that until now could only be started asynchronously.

The process stays **fully durable**. Every transition is persisted exactly as for an asynchronously started process; the caller is only *watching* for the reply. If the caller hangs up, times out, or the pod holding its connection dies, nothing about the process changes — and a retry with the same idempotency key finds the same process and its reply.

## 1. Say where the process answers: the `REPLY` step

```json
{ "id": "confirm", "type": "REPLY", "name": "Booking confirmed",
  "replyVariables": ["bookingId", "confirmation"], "preconditionStepId": "charge" }
```

The reply is either:

| field | the reply is |
|---|---|
| `replyVariables` | a JSON object with one member per listed variable (a variable that is not set is present as `null`) |
| `replyExpression` | the value of a JEXL expression over the process variables — e.g. `{'bookingId': bookingId, 'status': 'CONFIRMED'}` |
| `replyTemplate` | a [payload template](/guides/payload-templates/) — JSON with `${…}` leaves, e.g. `{ "bookingId": "${bookingId}", "total": "${total * 1}" }` |
| none | `{}` — a plain acknowledgement |

At most one of the three. A `REPLY` is engine-internal: it completes in the step-over that reaches it, and the flow carries on.

- **Before `END`** it answers with the result.
- **Earlier**, the caller gets its answer while the process continues durably. An early reply is a commitment: if a later step fails, the process compensates as usual, but the caller's answer does not change (its `processStatus` does — see below).

**A process replies at most once.** Validation — in the engine on import, and in the [Maven plugin](/reference/maven-plugin/) at build time, running the same code — rejects a definition in which two `REPLY` steps could both run in one instance. Two `REPLY` steps are fine only when an **exclusive split** separates them: different branches of a `CHOICE` that every way into both passes through, or a step's normal route versus its `onTimeoutStepId` route. `FORK` branches, several successors of a plain step, and guarded links are all treated as possibly running together. A `REPLY` cannot be a compensation step, and a `DYNAMIC` step cannot inject one.

A `REPLY` in a process that was started asynchronously still records its reply (visible in the UI), so the same definition runs identically either way.

## 2. Make the definition sync-invocable

```json
{
  "id": "book-trip",
  "syncInvocation": {
    "enabled": true,
    "onFailure": "REPLY_IMMEDIATELY",
    "onLockBusy": "WAIT",
    "defaultDeadlineMs": 5000
  },
  "steps": [ ... ]
}
```

| property | default | meaning |
|---|---|---|
| `enabled` | `false` | the invocation endpoint accepts this definition. At least one `REPLY` must be reachable; a path that can reach `END` without passing one is a **warning** |
| `onFailure` | `REPLY_IMMEDIATELY` | when a process that fails before its `REPLY` answers — see [the failure contract](#5-the-failure-contract) |
| `onLockBusy` | `WAIT` | with a `processLock`: queue FIFO (`WAIT`), or refuse with 409 without creating an instance (`FAIL`) |
| `defaultDeadlineMs` | engine default | how long a caller waits when it does not say |

`syncInvocation` is part of the definition's version: changing it mints a new version, and in-flight processes keep the one they started with.

## 3. Invoke it

```http
POST /workflow/api/definitions/book-trip/invocations
Idempotency-Key: 7f0c1c9e-…            (required)
Prefer: wait=10                        (optional, seconds; capped by workflow.sync.max-deadline-ms)
Content-Type: application/json

{ "businessKey": "trip-4711", "variables": { "customerId": "C-9", "nights": 3 } }
```

Variables are process variables, which are strings: a JSON string passes through, anything else (a number, an object) is stored as its JSON text.

| answer | status | body |
|---|---|---|
| the process replied | **200** | the envelope, `reply` = what the `REPLY` step emitted |
| the process ended along a path with no `REPLY` | **200** | `outcome: COMPLETED_WITHOUT_REPLY`, no `reply` |
| the process failed or was cancelled before replying | **502** | `outcome`, `compensation`, `error` (see §5) |
| the deadline passed first | **202** + `Location` + `Retry-After` | the envelope without a reply — **the process carries on** |
| missing `Idempotency-Key` | 400 | |
| the key was already used for a different request | 422 | |
| not sync-invocable, disabled, business key taken, lock busy (`onLockBusy: FAIL`), or placed on another shard | 409 | a problem with `reason` (and `shard` for `ON_ANOTHER_SHARD`) |
| too many callers waiting on this node | 429 + `Retry-After` | nothing was created |

The envelope:

```json
{
  "invocationId": "…", "processId": "…",
  "outcome": "REPLIED", "compensation": "NONE",
  "reply": { "bookingId": "B-1", "confirmation": "C-77" },
  "processStatus": "COMPLETED", "repliedAt": "2026-09-24T10:13:04",
  "location": "/workflow/api/invocations/…"
}
```

`outcome` is the answer; `processStatus` is where the process is *now* — after an early reply, `RUNNING` (and later perhaps `COMPENSATED`).

**Reading the result later** — after a 202, a lost connection, or from another client:

```http
GET /workflow/api/invocations/{invocationId}                            (Prefer: wait=N long-polls)
GET /workflow/api/definitions/{id}/invocations?idempotencyKey=…         (when you only have the key)
```

**Idempotency.** `(definition, Idempotency-Key)` identifies an invocation. A retry with the same key never starts a second process: it joins the running one, or gets the reply already given. The same key with a different body (business key or variables) is refused 422. Invocations are remembered for `workflow.sync.retention` (24 h); after that the key is free again, and the reply is still on the process.

The API uses the same optional `X-Api-Key` as the [message API](/reference/configuration/#http-security), and a definition's `requiredScopes` / `requiredRoles` apply to the caller (403).

## 4. Watch it: a progress stream (SSE)

Send `Accept: text/event-stream` to the same POST (or GET) and, instead of one JSON at the end, receive Server-Sent Events while the process runs:

| event | data |
|---|---|
| `status` | `{ processId, processStatus }` — the first event, and each status change |
| `step` | `{ stepId, stepName, type, status, at }` — each step status change |
| `log` | `{ stepId, level, message, at }` — the process's log lines |
| `reply` | the envelope above — the stream ends here |
| `timeout` | `{ invocationId, processId, location }` — the deadline passed first |

`?follow=true` keeps the stream open past an early reply until the process finishes (or the deadline). The stream reads the persisted process, so it works whichever node runs the process. **Resuming:** reconnect with `Last-Event-ID`; delivery is at-least-once — events from a few seconds before that id are sent again — and every event's data has a stable `key`, so drop the ones you have.

## 5. The failure contract

A failure **before** the `REPLY` still answers the caller — with **502** and an `outcome`:

| `onFailure` | answered | `outcome` / `compensation` |
|---|---|---|
| `REPLY_IMMEDIATELY` (default) | as soon as the process fails | `FAILED` / `IN_PROGRESS` while the saga rolls back — or `NONE` if there was nothing to undo |
| `REPLY_AFTER_COMPENSATION` | when the rollback ends | `COMPENSATED` / `DONE`, or `COMPENSATION_FAILED` / `FAILED`; `FAILED` / `NONE` if there was nothing to undo |

`error` names the step that failed and its last error line. A cancelled process answers `CANCELLED`. **The first answer stands**: an operator who retries a failed process that then reaches its `REPLY` changes the process, not the answer the caller got.

A *business* "no" is not a failure: model it as a `REPLY` on its own `CHOICE` branch — it is answered 200 with whatever that branch replies.

## 6. Locks

With a [process-level lock](/reference/step-types/), `onLockBusy: WAIT` (default) queues the instance FIFO like any other — a long wait becomes a 202 through the deadline, and **the instance will run** when admitted, even if the caller gave up. `onLockBusy: FAIL` refuses the invocation 409 + `Retry-After` when the key is held: no instance, no place in the queue, and the idempotency key stays free for the retry. Step-level `LOCK` steps always wait.

## 7. How fast, and across how many nodes

**The fast path.** The node that takes the request drives the new process itself: it handles the process's outbox rows one after another instead of each transition crossing the relay (and, in kafka mode, the broker). Every transition is still written to the outbox exactly as before; if the node dies mid-drive, its claims lapse and the relay carries on — at-least-once, as always. The drive stops where the work is somebody else's: a Kafka worker, a timer, a message, a human task. In kafka mode that means only engine-side steps (`START`, gateways, `SEND_MESSAGE`, `LOCK`, `REPLY`, …) run inline; embedded tasks run inline in embedded mode.

**Across nodes.** The reply is a committed row. The node holding the connection hears about it immediately if it recorded it itself; on PostgreSQL, another node's reply arrives by `LISTEN/NOTIFY` (issued in the reply's own transaction, so it is exactly as durable as the reply); on any database, a poll over all waiting callers (`workflow.sync.poll-interval-ms`) is the fallback and the bound. `LISTEN` needs a direct connection: **not** through PgBouncer in transaction mode.

Measured on one developer machine (PostgreSQL + Kafka in containers, 200 sequential invocations, see `SyncLatencyBenchmarkTest`): with two nodes and two Kafka-worker steps, p99 from request to reply ~22 ms; engine-side steps only, ~20 ms. Read ratios, not absolutes — see [Performance](/guides/performance/).

**Sharding.** An invocation is placed on the shard that received it: its idempotency key (and business key) are claimed there in the fleet's placement store. A retry that lands on another shard is answered 409 `ON_ANOTHER_SHARD` with the owning `shard`, so a gateway can route it; route by idempotency key to avoid it.

## Configuration

| property | default | |
|---|---|---|
| `workflow.sync.default-deadline-ms` | `5000` | wait when neither the caller nor the definition says |
| `workflow.sync.max-deadline-ms` | `30000` | the longest any caller may wait |
| `workflow.sync.max-waiting` | `200` | callers (and streams) waiting per node; then 429 |
| `workflow.sync.poll-interval-ms` | `250` | the fallback poll for replies recorded elsewhere |
| `workflow.sync.retention` | `PT24H` | how long an invocation (its key) is remembered |
| `workflow.sync.max-reply-bytes` | `262144` | larger replies fail the `REPLY` step |
| `workflow.sync.stream-interval-ms` | `200` | how often a progress stream looks |
| `workflow.sync.inline.enabled` | `true` | the fast path |
| `workflow.sync.inline.threads` | `8` | processes driven inline at once per node; beyond, the normal path |
| `workflow.sync.inline.max-steps` | `64` | messages per inline drive before the rest goes to the relay |
| `workflow.sync.inline.claim-lease-ms` | `30000` | how long an inline claim survives a dead node |
| `workflow.sync.notify.enabled` | `true` | PostgreSQL `LISTEN/NOTIFY` wake-up |

Metrics (`eventconductor.sync.*`) and the Grafana panels are listed in [Observability](/reference/observability/). The process view shows a **Synchronous** badge and a **Reply** tab.
