# eventconductor-worker

Serve [EventConductor](https://github.com/miguelperezcolom/eventconductor) workflow tasks from Python
over HTTP long-polling — no Kafka client, no JVM, no third-party dependencies (standard library only).

The engine holds the tasks of the topics you list in `workflow.workers.http.topics` in a durable
queue; this worker long-polls for them, runs your handler, heartbeats while it works and answers.

## Example

```python
from eventconductor_worker import Worker, TaskError

worker = Worker("http://localhost:8080", api_key="my-worker-key", concurrency=4)

@worker.task("payments")
def charge(task):
    if task.variables["amount"] == "0":
        raise TaskError("ZERO_AMOUNT", "nothing to charge", retryable=False)
    return {"chargeId": f"ch-{task.process_id}"}

worker.run()  # Ctrl-C / SIGTERM: stops polling and lets running tasks finish
```

Engine side (`application.yml`):

```yaml
workflow:
  workers:
    http:
      enabled: true
      topics: [payments]
      api-keys:
        python-payments: ${PAYMENTS_WORKER_KEY}
```

and a step with `"type": "ACTION", "topic": "payments"`.

## What a handler gets and gives

- `task.variables` — the process variables, as the engine holds them (strings; `json.loads` a
  structured one). Also `task.id`, `task.process_id`, `task.step_id`, `task.task_id`, `task.attempt`,
  `task.delivery` (above 1: a redelivery).
- Return a `dict` of output variables (any JSON value; non-strings are stored as their JSON text), or `None`.
- Raise `TaskError(code, message, retryable=True)` for a declared failure. `retryable=False` fails
  the step at once instead of spending its `retries`.
- Any other exception fails the task **retryably**, with the exception as the reason.
- `task.cancelled` / `task.wait_cancelled(timeout)` — the engine cancelled the task (the step timed
  out, the process was cancelled, or the task was handed to another worker): stop, nothing is reported.
- `task.inject(steps)` — a DYNAMIC step adds steps to its process; return to complete it.

## Idempotency

Delivery is **at least once**. A task whose lease expires — the worker paused, crashed or lost the
network — is handed to another worker, and both may run it. Make handlers idempotent (key side
effects by `task.id`, which is stable across redeliveries and retries of the step). The engine
protects itself: a reply for an attempt it has since replaced is refused (`409`), and only the first
reply for an attempt is taken.

## Behaviour

| | |
|---|---|
| Concurrency | `concurrency` handlers run at once on a thread pool; the worker only asks for as many tasks as it has free slots. |
| Leases | `lease_ms` (default 60 s); a heartbeat every third of it keeps a long task ours. |
| Replies | Retried with exponential backoff and jitter on network errors, `429` and `5xx`; dropped (logged) on `404`/`409` — the engine no longer wants them. |
| Activation errors | Retried with backoff; `429` (too many waiting workers on that node) after a second. |
| Shutdown | `stop()` / SIGINT / SIGTERM: no new activations; running tasks get `shutdown_grace` seconds. |

`EngineClient` is the protocol without threads, one method per endpoint, if you would rather drive it yourself.

## Tests

```bash
python -m venv .venv && .venv/bin/pip install -e '.[test]' && .venv/bin/pytest
```

The suite runs against an in-process fake of the engine's worker API. `tests/test_engine_integration.py`
runs against a real engine when `EC_ENGINE_URL` (and `EC_API_KEY`, `EC_TOPIC`) are set.

Not published to PyPI yet: install from this directory (`pip install ./sdks/python/eventconductor-worker`).
