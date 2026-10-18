# Plan: HTTP long-poll workers and a Python worker SDK (2.30)

> Status: **IMPLEMENTED** on branch `feat/http-workers`, one PR.

## 1. Goal

A worker written in **any language** serves EventConductor tasks over plain HTTP — no Kafka client,
no JVM — with the same guarantees as the Kafka and embedded workers: at-least-once delivery, the
`attempt` echo that protects the engine from late replies, cancellation, declared non-retryable
failures and DYNAMIC injection. Parity target: Zeebe job workers / Conductor polling workers.

Non-goals: a Java HTTP client (Java users have embedded and Kafka workers); publishing the Python
SDK to PyPI; push delivery (webhooks to the worker) — pull only.

## 2. What exists (findings)

- **Dispatch seam.** Every task leaves the engine through one port,
  `DownstreamEventPublisher.publish(event, topic)` — `StartStepExecutionUseCase` (task),
  `StepExecutionStatusUpdatedEventHandler` / `CancelProcessUseCase` / `StepOverProcessUseCase` /
  `RestartProcessUseCase` (cancellations, sent to the topic the task went to). Implementations:
  `EmbeddedDownstreamEventPublisher` (in-process `EmbeddedTaskExecutor`) and
  `KafkaDownstreamEventPublisher` (topic = binding). ⇒ HTTP routing is a **decorator on that port**,
  keyed by topic; nothing in the domain changes.
- **Reply seam.** Kafka workers answer with `TaskStatusChanged` (+ `TaskLogEmitted` for the reason,
  `StepsInjected` for DYNAMIC) on `upstream`; the engine's own `UpstreamEventPublisher` publishes
  exactly those events in both modes (embedded: synchronous `ProcessUpstreamEventUseCase`; kafka:
  `upstream` keyed by processId, so the owning pod handles it). ⇒ HTTP replies are turned into those
  same events and published through `UpstreamEventPublisher`; the engine cannot tell an HTTP worker
  from a Kafka one.
- **Attempt.** `TaskExecutionRequested.attempt` = the step execution's `dispatchCount` (#522). The
  engine drops a `TaskStatusChanged` whose attempt is not current. A redelivery of the *same* dispatch
  (lease expiry) keeps the attempt.
- **Non-retryable.** `FailureMarkers.NON_RETRYABLE` prefix on the reason + `nonRetryable` flag on the reply.
- **Cancellation.** `TaskCancellationRequested(taskId=stepExecutionId)` on the task's topic.
- **Heartbeats.** None in the Kafka protocol (a worker may send `RUNNING`). HTTP needs one for leases.
- **Stalled-step report.** `TimeoutScheduler` counts PENDING/RUNNING ACTION/RULE steps with no
  deadline — the step stays PENDING while its task sits in the HTTP queue, so the report keeps working.
- **Claim pattern.** `DbLockDialect.claimPendingOutboxSql()` — `SELECT … LIMIT ? FOR UPDATE SKIP LOCKED`
  (Oracle: `ROWNUM` subquery). Same shape reused for the task queue.
- **Admission.** `SyncInvocationController` caps waiting callers with a `Semaphore` (429 past it) and
  never parks servlet threads (`DeferredResult`).
- **Auth.** `EngineApiAccess.verify` — `workflow.message-api.api-key` / Spring Security / allow-anonymous.

## 3. Design

### 3.1 Which tasks go over HTTP

`workflow.workers.http.enabled=true` and `workflow.workers.http.topics` — a list of step topics
(`downstream` is the topic of a step that names none), or `*`. A `BeanPostProcessor` wraps whichever
`DownstreamEventPublisher` the mode supplies (embedded or Kafka) in `HttpWorkerRoutingPublisher`:

- `TaskExecutionRequested` on a routed topic → enqueued in the HTTP task queue; otherwise delegated.
- `*` routes every topic **except** the built-in tasks (USER_TASK `complete-form`, RULE `evaluate-rule`,
  HTTP_CALL, LLM_CALL) — their own runtimes keep serving them. Naming their topic explicitly routes them.
- `TaskCancellationRequested` → offered to the queue first; delegated only when the queue did not
  hold the task and the topic is not explicitly routed (so Kafka/embedded workers still get theirs).

Kafka and embedded workers keep working side by side for every other topic.

### 3.2 Queue (`http_task`, Flyway V50) — why not the outbox

A new table, not the outbox. The outbox is a *relay* (rows Pending → Sent, drained by the engine,
retention purges Sent); a worker queue needs per-row **leases** (holder, token, expiry), per-row
cancellation and topic-filtered claims by external callers. Overloading the outbox would put worker
traffic on the relay's hot index and change the meaning of its statuses. The table is small (one row
per task currently owed a worker; the row is deleted when the worker answers).

```
http_task(id PK = step execution id, process_id, workflow_definition_id, step_id, task_id, topic,
          variables_json, attempt, created_at, lease_until, worker_id, lease_token, deliveries,
          cancelled BOOLEAN)   + index (topic, created_at), index (lease_until)
```

Portable DDL with `IF NOT EXISTS` like V39–V46, also declared as a JPA entity for `ddl-auto` schemas.
Memory persistence → `InMemoryHttpTaskQueue` (tests, single JVM). JPA → `JdbcHttpTaskQueue`.

- **Enqueue = upsert by id.** A new dispatch of the same step execution (retry, restart: higher
  attempt) replaces the row and clears its lease; a duplicate of the same attempt is a no-op. In JPA
  mode it joins the relay's transaction, so the row commits with the dispatch; the wake-up is sent
  after commit.
- **Claim.** `SELECT id … WHERE topic IN (…) AND cancelled = false AND (lease_until IS NULL OR
  lease_until < now) ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED`, then stamp
  `lease_until`, `worker_id`, a fresh `lease_token`, `deliveries+1`. Concurrent activations on any
  number of pods get disjoint tasks (PostgreSQL Testcontainers test). Dialect method
  `DbLockDialect.claimHttpTasksSql(n)`; Oracle uses the `ROWNUM` form.
- **Lease expiry = redelivery.** No sweeper: an expired lease simply matches the claim again.
  `deliveries` counts it (metric `redelivered`). Workers must be idempotent (as Kafka workers are).
- **Cancellation.** Unleased → row deleted. Leased → `cancelled=true`; the next heartbeat answers
  `cancelled: true` and drops the row; otherwise it is purged once its lease expires.
- **Maintenance** (every `maintenance-interval`): purge cancelled+expired rows and orphans older than
  `orphan-retention` (a row whose step moved on without a cancellation), refresh the queue gauges.

### 3.3 API — `/workflow/api/workers`

Auth: `X-Api-Key` must be one of `workflow.workers.http.api-keys` (named worker credentials, which
grant **only** the worker API) or pass `EngineApiAccess.verify` (engine api-key / Spring Security /
allow-anonymous). Never anonymous otherwise.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /activate` | `{workerId, topics[], maxTasks, leaseMs, waitMs}` | `200 {tasks:[{id, processId, workflowDefinitionId, stepId, taskId, topic, variables{}, attempt, delivery, leaseToken, leaseExpiresAt}]}` — long-polls up to `min(waitMs, max-wait)`; empty list on timeout; `429` past `max-waiting` |
| `POST /tasks/{id}/complete` | `{attempt, variables{}}` | `204`; `404` gone; `409` stale attempt |
| `POST /tasks/{id}/fail` | `{attempt, code, message, retryable}` | `204`; `404`; `409` |
| `POST /tasks/{id}/heartbeat` | `{leaseToken, extendMs}` | `200 {cancelled, leaseExpiresAt}`; `404` gone; `409` lease lost (re-leased) |
| `POST /tasks/{id}/inject` | `{attempt, steps:[…]}` (DYNAMIC) | `204`; `404`; `409` |

- `variables` out: `{name: value}` (values are the engine's strings). In: any JSON; strings kept,
  anything else stored as its JSON text (same as `VariableBinding`).
- `fail`: reason `code: message`; `retryable=false` → `FailureMarkers.nonRetryable` + `nonRetryable`
  flag, so the step fails at once instead of spending its retries.
- **Late replies.** `attempt` must equal the queued dispatch's: a reply for a superseded dispatch
  (step retried/restarted) answers 409 and never reaches the engine (which would drop it anyway).
  Within one dispatch the first reply wins and removes the row; a second (a worker whose lease had
  expired and was re-leased) answers 404. Heartbeats need the current `leaseToken`.
- Reply order: publish upstream first, then delete the row — a failed publish answers 503, the row
  stays and the worker retries.

### 3.4 Long-poll

`DeferredResult`, never a parked servlet thread. One dispatcher thread serves waiters **FIFO** (the
longest-waiting worker first — fairness across workers; tasks oldest-first across topics — no topic
starves), woken immediately by a local enqueue/cancel/release and otherwise every `poll-interval`
(tasks enqueued by another pod, expired leases). An empty claim for a topic set is remembered for the
rest of that pass, so N idle waiters cost one query per topic set per interval, not N. Admission:
`Semaphore(max-waiting)` → 429. `waitMs` capped by `max-wait`; `maxTasks` by `max-tasks`;
`leaseMs` by `max-lease`.

### 3.5 Metrics (`eventconductor.workers.http.*`)

`enqueued{topic}`, `activated{topic}`, `redelivered{topic}`, `replies{outcome=completed|failed|injected|stale|gone}`,
`cancelled`, `rejected{reason}`, gauges `waiting` (long-polls) and `queued{state=available|leased}`.

### 3.6 Configuration (`workflow.workers.http.*`)

`enabled` (false), `topics` ([]), `api-keys` (map name→key), `max-wait` (30s), `default-lease`
(60s), `max-lease` (30m), `max-tasks` (100), `max-waiting` (200), `poll-interval` (1s),
`maintenance-interval` (30s), `orphan-retention` (7d).

## 4. Python SDK — `sdks/python/eventconductor-worker`

Stdlib only (`urllib`, `threading`, `json`); typed (`py.typed`); Python ≥ 3.9.

```python
from eventconductor_worker import Worker, TaskError
worker = Worker("http://localhost:8080", api_key="…", worker_id="py-1", concurrency=4)

@worker.task("payments")
def charge(task):
    if task.variables["amount"] == "0":
        raise TaskError("ZERO_AMOUNT", "nothing to charge", retryable=False)
    return {"chargeId": "ch-1"}

worker.run()   # SIGINT/SIGTERM → graceful shutdown
```

- One poller thread long-polls for `free slots` tasks; a thread pool runs handlers; a heartbeat thread
  extends leases at a third of the lease and flips `task.cancelled` on `cancelled: true`/404/409.
- Replies retried with exponential backoff + jitter on network errors/5xx/429; 404/409 → dropped
  (logged): the engine no longer wants that answer.
- Graceful shutdown: stop polling, let in-flight handlers finish within `shutdown_grace`.
- `task.inject(steps)` for DYNAMIC. Handler exceptions → retryable failure with the exception text.
- Tests: pytest against an in-process fake server implementing the protocol; an engine integration
  test runs when `EC_ENGINE_URL` is set. CI job `python-sdk` on PRs touching `sdks/python/**`.

## 5. Tests

- workflow-engine: MockMvc for every endpoint + auth (worker key, engine key, anonymous refused,
  allow-anonymous), service unit tests (late reply, stale attempt, cancellation via heartbeat, lease
  expiry/redelivery, long-poll timing — woken by enqueue, no busy loop, 429), routing decorator, H2
  queue contract, PostgreSQL Testcontainers concurrent activation (disjoint claims).
- workflow-e2e: memory and JPA — a workflow whose ACTION is served by a Java test client over the
  endpoints (MockMvc), including retry after failure and cancellation.

## 6. Deferred

- Reporting `RUNNING` (resetting a step timeout) from a heartbeat — a step `timeout` bounds the whole task.
- Binding a worker credential to a set of topics.
- Push delivery / gRPC streaming; a Java HTTP client; PyPI publishing.
