---
title: HTTP worker API
description: The long-poll protocol a worker in any language uses to serve EventConductor tasks over HTTP — endpoints, leases, attempts, statuses and reason codes.
---

The engine's side of [workers over HTTP](/guides/http-workers/). All endpoints are `POST`, take
and return JSON, and live under `/workflow/api/workers`. Enabled with
`workflow.workers.http.enabled=true`; see [Configuration](/reference/configuration/#workers-over-http-workflowworkershttp).

## Authentication

`X-Api-Key: <key>` where the key is one of `workflow.workers.http.api-keys` (a worker credential:
it opens this API only) or the engine's `workflow.message-api.api-key`. With no key configured the
request needs the application's Spring Security or `workflow.message-api.allow-anonymous=true`;
otherwise `401`.

## `POST /activate`

Long-polls for tasks and leases them to the caller.

```json
{ "workerId": "py-1", "topics": ["payments"], "maxTasks": 4, "leaseMs": 60000, "waitMs": 20000 }
```

| Field | Default | |
|---|---|---|
| `workerId` | required | Who is asking (≤ 255 chars); recorded on the lease, shown in logs. |
| `topics` | required | Step topics this worker serves. |
| `maxTasks` | `1` | At most this many tasks (capped by `max-tasks`). Ask for as many as you have free slots. |
| `leaseMs` | `default-lease` | How long the tasks are yours without a heartbeat (1 s … `max-lease`). |
| `waitMs` | `0` | How long to wait when there is nothing (capped by `max-wait`); `0` answers at once. |

Answer `200`, possibly with no tasks when the wait ran out:

```jsonc
{ "tasks": [ {
    "id": "6f1c…",                     // the task (the step execution) — stable across redeliveries
    "processId": "a41e…", "workflowDefinitionId": "order", "stepId": "charge",
    "taskId": "charge@2",              // the task contract, when the step references one
    "topic": "payments",
    "variables": { "amount": "42", "currency": "EUR" },
    "attempt": 1,                      // echo it on every reply
    "delivery": 1,                     // how many times it was handed out; > 1 = redelivered
    "leaseToken": "c0d3…",             // present it on heartbeats
    "deadline": "2026-10-18T10:01:00Z" // when the lease expires
} ] }
```

Variables are the engine's strings; a structured value is its JSON text.

## `POST /tasks/{id}/complete`

```json
{ "attempt": 1, "variables": { "chargeId": "ch-1", "total": 42.5, "lines": [ { "sku": "A" } ] } }
```

Output variables may be any JSON: strings are kept as they are, anything else is stored as its JSON
text (what a Java worker's output becomes). `204`.

## `POST /tasks/{id}/fail`

```json
{ "attempt": 1, "code": "CARD_DECLINED", "message": "insufficient funds", "retryable": true }
```

The reason `CARD_DECLINED: insufficient funds` is logged on the process. `retryable` (default
`true`) spends the step's `retries`; `false` fails the step at once. `204`.

## `POST /tasks/{id}/heartbeat`

```json
{ "leaseToken": "c0d3…", "extendMs": 60000 }
```

Extends the lease to now + `extendMs` (capped by `max-lease`). Answer `200`:

```json
{ "cancelled": false, "deadline": "2026-10-18T10:02:00Z" }
```

`{"cancelled": true}` means the engine cancelled the task: stop, report nothing. Heartbeat every
third of the lease or so; a lease that expires hands the task to another worker.

## `POST /tasks/{id}/inject`

For DYNAMIC steps: add steps to the process, then `complete` the task.

```json
{ "attempt": 1, "steps": [ { "id": "ship-a", "type": "ACTION", "topic": "shipping", "preconditionStepId": "plan" } ] }
```

The batch is validated engine-side; a rejected batch fails the DYNAMIC step with the reason. See
[Dynamic workflows](/guides/dynamic-workflows/). `204`.

## Errors

Errors are RFC 9457 problem details with a `reason`:

| Status | `reason` | Meaning | Worker should |
|---|---|---|---|
| `400` | `INVALID` | Malformed request (missing `attempt`, `leaseToken`, `steps`, …). | Fix the call. |
| `401` | — | Missing or wrong `X-Api-Key`. | Fix the credential. |
| `404` | `NOT_FOUND` | No such task waiting for a reply: answered already (by another worker after a redelivery), cancelled and dropped, or never served over HTTP. | Drop the task. |
| `409` | `STALE_ATTEMPT` | The step ran again; this reply is for an attempt the engine abandoned. | Drop the task. |
| `409` | `LEASE_LOST` | Heartbeat: the lease expired and the task was given to another worker. | Stop; drop the task. |
| `409` | `CANCELLED` | The engine cancelled the task; the reply is not wanted. | Drop the task. |
| `429` | `OVERLOADED` | Too many activations waiting on this node. `Retry-After: 1`. | Retry after a second. |
| `503` | `UNAVAILABLE` | The engine could not take the reply right now. `Retry-After: 1`. | Retry with back-off — the task stays yours. |

## Guarantees

- **At least once.** A task is redelivered when its lease expires; a worker must be idempotent.
- **No lost replies.** A reply the engine does not take answers `503` and the task stays queued.
- **No late replies.** A reply for an abandoned attempt never reaches the process.
- **No duplicate leases.** Concurrent activations — on any number of nodes — never get the same
  task while its lease runs (`SELECT … FOR UPDATE SKIP LOCKED` on the shared queue).
