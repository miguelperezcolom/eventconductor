---
title: Workers over HTTP (any language)
description: Serve ACTION steps from a worker in any language — Python, Go, Node, a shell script — by long-polling the engine over HTTP, without Kafka.
---

A worker does not have to be a JVM process or speak Kafka. With **workers over HTTP** the engine
holds the tasks of the topics you choose in a durable queue, and a worker in any language
**long-polls** for them, runs its business logic, heartbeats while it works and answers — over
plain HTTP and JSON. It is the same model as Zeebe job workers or Conductor polling workers, and it
runs side by side with [embedded and Kafka workers](/guides/workers/): only the topics you list are
served over HTTP.

What you keep from the other transports: at-least-once delivery, the `attempt` check that keeps a
late reply from corrupting a retried step, cancellation, declared non-retryable failures, and
DYNAMIC step injection.

## 1. Turn it on in the engine

```yaml
workflow:
  workers:
    http:
      enabled: true
      topics: [payments]            # the step topics served over HTTP; "*" = every topic
      api-keys:                     # worker credentials (X-Api-Key), good for the worker API only
        python-payments: ${PAYMENTS_WORKER_KEY}
```

- It works in every mode: **embedded + memory** (tests), **embedded + JPA** and **Kafka + JPA**.
  With JPA the queue is the `http_task` table (Flyway `V50`), shared by every node, so a worker may
  call any node of a cluster.
- `topics` lists step topics. A step with no `topic` is on `downstream`. `*` serves every topic
  *except* the built-in tasks (USER_TASK, RULE, HTTP_CALL, LLM_CALL), which keep their own
  runtimes unless you name their topic explicitly.
- Every other topic still goes to the embedded or Kafka workers.
- With [sharding](/guides/sharding/), each shard has its own database and so its own queue: point
  a worker (or one worker instance per shard) at each shard.

A step served over HTTP is an ordinary ACTION (or DYNAMIC) step:

```json
{ "id": "charge", "type": "ACTION", "topic": "payments", "retries": 3, "timeout": 120000,
  "preconditionStepId": "start" }
```

## 2. Write the worker

### Python

The [Python SDK](https://github.com/miguelperezcolom/eventconductor/tree/main/sdks/python/eventconductor-worker)
(`sdks/python/eventconductor-worker`, standard library only) handles polling, concurrency,
heartbeats, retries and shutdown:

```python
from eventconductor_worker import Worker, TaskError

worker = Worker("http://localhost:8080", api_key="my-worker-key", concurrency=4)

@worker.task("payments")
def charge(task):
    if task.variables["amount"] == "0":
        raise TaskError("ZERO_AMOUNT", "nothing to charge", retryable=False)
    return {"chargeId": f"ch-{task.process_id}"}

worker.run()
```

### Anything else

The protocol is five POSTs; a minimal worker with `curl`:

```bash
KEY=my-worker-key; ENGINE=http://localhost:8080/workflow/api/workers

# Wait up to 20 s for one task of topic "payments", leased for 60 s.
TASK=$(curl -s -X POST $ENGINE/activate -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"workerId":"sh-1","topics":["payments"],"maxTasks":1,"leaseMs":60000,"waitMs":20000}' | jq '.tasks[0]')

ID=$(echo "$TASK" | jq -r .id); ATTEMPT=$(echo "$TASK" | jq .attempt)

# ... do the work ...

curl -s -X POST $ENGINE/tasks/$ID/complete -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{\"attempt\": $ATTEMPT, \"variables\": {\"chargeId\": \"ch-1\"}}"
```

The full contract — every field, status and reason code — is in the
[HTTP worker API reference](/reference/http-worker-api/).

## How it behaves

**Long-polling.** An activation waits (up to `waitMs`, capped by `workflow.workers.http.max-wait`)
until there is a task for one of its topics. A task queued on the node a worker is waiting on is
handed over at once; a task queued on another node is picked up within `poll-interval`. Waiting
workers are served first come, first served, and tasks oldest first across topics. Each node caps
waiting activations (`max-waiting`); past it, a `429` asks the worker to come back in a second.

**Leases.** An activated task is the worker's until its lease (`leaseMs`) expires. A worker that
needs longer **heartbeats**, which extends the lease. A lease that expires — the worker crashed,
paused or lost the network — makes the task available again, and another worker gets it
(`delivery` counts the hand-outs).

**Idempotency.** Delivery is therefore *at least once*: two workers may run the same task. Make the
work idempotent — key side effects by the task `id`, which stays the same across redeliveries and
retries. The engine protects itself:

- every reply carries the `attempt` it was handed. When a step runs again (a retry after a failure
  or a timeout, a restarted process) it gets a new attempt, and a reply for the old one is refused
  with `409 STALE_ATTEMPT` — it never reaches the process;
- within one attempt, the first reply wins; a second finds the task gone (`404`).

**Cancellation.** When the engine gives up on a task — the step timed out, the process was
cancelled, a saga rolled back — a task nobody holds is dropped, and a held one is flagged: the
worker's next heartbeat answers `{"cancelled": true}`. Stop and report nothing.

**Failures.** `fail` with `retryable: true` spends the step's `retries` (with their back-off); with
`retryable: false` the step fails at once and compensation, if any, runs. The `code` and `message`
land on the process's Errors tab.

**Timeouts.** A step `timeout` bounds the task end to end — heartbeats extend the lease, not the
step's deadline.

## Security

Every call needs a credential: an `X-Api-Key` listed in `workflow.workers.http.api-keys` (it opens
the worker API and nothing else), or whatever opens the rest of the engine API — the engine's
`workflow.message-api.api-key`, the application's Spring Security, or
`workflow.message-api.allow-anonymous=true` on an isolated deployment. If Spring Security protects
the application and your workers use worker keys, permit `/workflow/api/workers/**` in the filter
chain so the key reaches the engine.

## Observing it

`eventconductor.workers.http.*` meters count enqueued, activated, redelivered and cancelled tasks and
replies by outcome, and gauge the waiting activations and the queue — see
[Observability](/reference/observability/#workers-over-http). A task waiting in the queue keeps its
step `PENDING`, so the stalled-step report (`eventconductor.steps.stalled`) still counts steps that
have no timeout and no worker.

See also: [Configuration](/reference/configuration/#workers-over-http-workflowworkershttp).
