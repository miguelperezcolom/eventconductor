---
title: The Test Worker
description: Drive a workflow through any outcome — slow tasks, failures, retries, timeouts — without writing a worker for it.
---

Testing a workflow means answering its tasks, and answering its tasks normally means writing a
worker. That is fine once. It stops being fine when the question is "what does this saga do if the
payment step fails on the second attempt and the notification step never answers at all" — every
scenario is another worker, or another branch inside one, and the workflow under test is soon
outnumbered by the scaffolding around it.

The **test worker** answers instead. It does no work: it takes the scenario you give it, waits the
time you asked for, says what you told it to say, and finishes the way you told it to finish. The
engine cannot tell the difference — the replies go out over the same topics, through the same
synchronous retry-or-throw path a real worker's do.

It ships as `apps/worker-standalone-app` (image `miguelperezcolom/worker-standalone-app`), and the
compose stack runs it on port **8107**.

## Stating a scenario

A process describes what its tasks should do in a `TEST_CONFIG` process variable — a JSON document,
carried as a string:

```json
{
  "default": { "durationMs": 200, "outcome": "COMPLETED" },
  "tasks": {
    "reserve-seat": {
      "durationMs": 500,
      "logs": [{ "type": "Info", "message": "checking inventory" }],
      "variables": [{ "name": "seatId", "value": "12A" }]
    },
    "charge-card": { "outcome": "ERROR", "reason": "card declined" },
    "notify":      { "outcome": "NO_REPLY" }
  }
}
```

Start the process with that variable and the whole run is determined: `reserve-seat` takes half a
second, logs a line and hands back `seatId`; `charge-card` fails with a reason your process log will
show; `notify` goes quiet and lets the step time out.

**The keys under `tasks` are step ids.** They are matched against the task's own id first and its
step id second, but for an `ACTION` step — every step a worker of yours will ever see — the engine
sends an empty task id: it fills that field only for `USER_TASK` (`complete-form`) and `RULE`
(`evaluate-rule`), and those go to the forms engine and the rule runtime. So write the step's id, as
above, where `reserve-seat` and `charge-card` are the `"id"` of the steps in the definition.

Anything a task does not state is inherited from `default`, and anything `default` does not state is
the built-in "take `worker.task-duration` and complete". So "make every task instant" is one line.

### What a task can say

| Field | Meaning |
|---|---|
| `durationMs` | How long the task takes before it replies. |
| `outcome` | `COMPLETED`, `ERROR`, or `NO_REPLY`. |
| `reason` | Why it failed. Sent as an `Error` log line before the failure, so the process log says what happened. `ERROR` only. |
| `logs` | `[{ "type": "Info" \| "Error", "message": "...", "atMs": 500 }]`. `atMs` is milliseconds into the task; omit it for "as it starts". |
| `variables` | `[{ "name": "...", "value": "..." }]`, reported with the reply and merged into the process. |
| `failuresBeforeSuccess` | Fail this many attempts, then succeed — the shape of a step under a retry policy. |
| `replyTimes` | Send the final reply more than once. |
| `ignoreCancellation` | Keep working and reply after the engine has cancelled the task. |

Unknown properties are **rejected**, and so is malformed JSON: the task fails with the parse error
as its reason, on the process you started. This is deliberate. A misspelled `durationMS` that
quietly meant "two seconds" would turn a test that proves nothing into a test that looks like it
passed.

### The scenarios worth knowing about

**`NO_REPLY`** reports `RUNNING` and then stops. That is a worker that took the task and hung, which
is what the engine's step timeout exists for — and no combination of `COMPLETED` and `ERROR`
produces it.

**`failuresBeforeSuccess`** counts how many times *this task execution* has been handed to the
worker. That is how the engine retries: it re-dispatches the **same** `taskExecutionId` and keeps
the attempt count itself, on the step execution — so the worker's count and the engine's
`attempt_count` agree, and `DIST-13` asserts they do. Pair it with `retries` on the step: without
enough retries the step simply fails, which is also a scenario worth writing.

One honest caveat: a Kafka redelivery of a task the worker has already seen counts as another
attempt. Nothing on `TaskExecutionRequested` tells a retry apart from a redelivery, so no worker
can — and a redelivery only happens when a worker throws, which for this one means the broker
refused its reply.

**`replyTimes` and `ignoreCancellation`** are a worker misbehaving on purpose. What the engine does
with a duplicate reply, or with a reply to a task it has already given up on, is a property of the
engine — and these are how you point at a running system and find out.

## Handing it to a running deployment

Everything above states a scenario from a test that starts its own process. Against a deployment —
an engine on Kubernetes, a definition someone pushed, a browser open on the console — nothing about
the scenario changes: it is the same `TEST_CONFIG` process variable, with the same fields. Only the
act of starting the process is different, and [Starting a Process](/guides/starting-a-process/) has
the three ways.

**From the orchestrator's UI.** *Workflow → Definitions*, open one, start a process, and add a
variable named `TEST_CONFIG` whose value is the JSON. This is the one to reach for when walking
somebody through a workflow: the same definition gives you a clean run, a rollback and a timeout one
after another, with nothing redeployed in between.

**On the `upstream` topic**, as part of the creation event:

```json
{
  "type": "process-creation-requested",
  "workflowDefinitionId": "order-fulfilment",
  "businessKey": "order-123",
  "variables": [
    { "name": "TEST_CONFIG",
      "value": "{\"default\":{\"durationMs\":200},\"tasks\":{\"charge-card\":{\"outcome\":\"ERROR\"}}}" }
  ]
}
```

**Programmatically**, as a `Variable` on the `ProcessCreationRequested` — what the test suite does,
and what a load generator should do.

The variable's *name* is matched without regard to case. It is the only lenient thing here.

### The escaping is where this goes wrong

A variable's value is a **string**, so a scenario is JSON inside JSON and every quote in it has to be
escaped. Getting that wrong fails in one of two ways, and they look nothing alike:

- **The event itself stops being valid JSON.** It never becomes an event at all: conversion fails
  before any handler runs, so no process is created — and the dead-letter parking that catches a
  poison event a handler cannot process never sees this one, because nothing ever handed it to a
  handler. The producer, meanwhile, exits 0: publishing bytes to a topic did succeed. A burst that
  produces no processes whatsoever, from a producer that reported success, is this.
- **The event parses and the `TEST_CONFIG` string does not.** The process is created, runs, and its
  first task fails with the parse error as its reason — on the process you started, where you can
  read it. This is the loud one, and it is the one to want.

So when a run comes up empty, **check that processes were created, not that the producer
succeeded.** The two are unrelated, and only the first is evidence.

Shell heredocs earn one specific warning, because a scenario is all braces: `${CONFIG:-{"a":1}}`
does not mean what it looks like — the first `}` closes the parameter expansion, and the escaping
that works around it leaks a backslash into the value, which produces exactly the silent first case
above. Assign the default in its own single-quoted statement instead of inside an expansion.

### One worker on `downstream` answers human tasks too

Out of the box this worker and the forms engine both bind the same default topic, `downstream`, in
different consumer groups — so both receive every task, and the test worker answers the `USER_TASK`
meant for a person before anybody is shown a form. Putting them in one consumer group does not fix
it: they then compete for the message, and which one wins is a coin toss.

Give the human tasks a topic of their own. Naming `"topic": "forms"` on the `USER_TASK` steps meant
for people, and binding the forms engine there, keeps the split explicit in the definition itself: a
step reaches a human because it says so, and every step that does not is answered by the worker —
which is usually exactly what you want from a definition under test. The same reasoning applies to
the test suite, where it is easier still: give the whole definition its own topic and bind the worker
to it.

## Driving it by hand

Not every process is one you can edit. The worker records every task it is given, and its UI at
**`/_worker`** has two pages:

- **Received tasks** — what arrived, newest first: which process and step, which attempt, what was
  played, and — the field people come here for — **which source answered it**. When a run surprises
  you, the first question is whether the reply came from the scenario the test wrote or from
  something left enabled in the table, and this answers it without anyone reasoning about
  precedence. The rows are read-only: this is the record of what happened.
- **Task overrides** — canned replies matched by workflow definition, step and task, any of which
  may be left blank for "any". They hold the same fields a scenario does. The most specific matching
  row wins, so a blanket row never shadows a precise one.

Read the step id off a received task, then create an override for it on the next page. There is no
one-click shortcut between them: a `@NotEditable` Crud offers no detail toolbar to hang a button
on, and making the history editable to get one back would cost more than the click it saves.

### Precedence

**`TEST_CONFIG` always wins.** A test states its scenario in the process it starts, so it gets that
scenario whatever is in the database. Overrides answer only the processes that state nothing.

This ordering is the point: an override that could outrank a process's own scenario would mean a
suite whose result depends on a table someone edited by hand last Tuesday.

## Running it

```yaml
worker:
  task-duration: 2s          # what a task takes when nothing says otherwise
  persistence: jpa           # or memory
```

With `worker.persistence=jpa` it keeps received tasks and overrides in PostgreSQL, which is what the
UI browses and edits. It owns its two tables outright and creates them itself
(`DDL_AUTO=update`), so pointing it at an empty schema is enough.

For a worker with no database at all — scenarios from `TEST_CONFIG` only, nothing surviving a
restart — run it with `SPRING_PROFILES_ACTIVE=memory`. That is usually what a CI suite wants: one
container, no volume.

Both shapes run tasks concurrently. The store calls are blocking — a query for the delivery count
and a write for the row — and they run on Reactor's elastic scheduler rather than on the thread that
carried the task in, so a database round trip does not hold the pool the other tasks in flight are
sharing. Under `jpa` that mattered: it is the difference between about 1.5 tasks genuinely in flight
and as many as the broker delivers.

:::note[Both were broken before 2.5.0]
The `memory` profile did not start at all — the Spring Data repositories were scanned whatever the
profile said, so the context asked for an entity manager the profile had deliberately removed. And
under `jpa` the blocking store calls sat on the Reactor pool, which collapsed the concurrency to
roughly one. There was no configuration in which this worker could be driven at load.
:::

### On Kubernetes

Next to an engine in a cluster it is one Deployment and one Service. Nothing about a scenario
changes — it is still the `TEST_CONFIG` variable on the process — but the wiring is worth writing
down, because two of these settings are the ones people get wrong.

```yaml
containers:
  - name: worker
    image: miguelperezcolom/worker-standalone-app:2.6.1   # track the engine's version
    env:
      - { name: SERVER_PORT,   value: "8091" }
      - { name: KAFKA_BROKERS, value: "redpanda:19092" }

      # The topic it listens on. `downstream` is the default a step goes to when it names none,
      # so this answers the whole definition — see the warning below.
      - name: SPRING_CLOUD_STREAM_BINDINGS_CONSUMEWORKEREVENT_IN_0_DESTINATION
        value: "downstream"

      # jpa keeps received tasks and overrides, which is what the UI browses. Use `memory`
      # (SPRING_PROFILES_ACTIVE=memory) for a worker that needs no database at all.
      - { name: WORKER_PERSISTENCE, value: "jpa" }
      # It owns its two tables outright and ships no migrations, so it creates them itself.
      - { name: DDL_AUTO,     value: "update" }
      - { name: DB_URL,       value: "jdbc:postgresql://postgres:5432/workflow" }
      - { name: DB_USERNAME,  valueFrom: { secretKeyRef: { name: ec-postgres, key: POSTGRES_USER } } }
      - { name: DB_PASSWORD,  valueFrom: { secretKeyRef: { name: ec-postgres, key: POSTGRES_PASSWORD } } }
      - { name: DB_POOL_SIZE, value: "8" }

      # What a task takes when the scenario does not say. Short, so a process walked through by
      # hand does not feel stuck.
      - { name: WORKER_TASK_DURATION, value: "2s" }
    ports:
      - { name: http, containerPort: 8091 }
    readinessProbe:
      httpGet: { path: /actuator/health/readiness, port: 8091 }
    livenessProbe:
      httpGet: { path: /actuator/health/liveness,  port: 8091 }
```

Point it at the same broker and, under `jpa`, the same database as the engine — it uses its own two
tables and touches nothing of the engine's. Its UI is then at `/_worker` behind whatever fronts the
cluster.

:::caution[Do not share `downstream` with the forms engine]
Both would receive every message, because they are in different consumer groups — so this worker
would answer the human tasks itself, cheerfully and wrongly, and a `USER_TASK` would be completed by
a scenario instead of by a person. Give the forms engine a topic of its own (`forms`) and have human
steps name it. Answering human tasks is the right behaviour when the whole definition is under test
and the *only* consumer is this worker; it is a bug the moment a forms engine is listening too.
:::

:::tip[Pin the tag to the engine's version]
The image is published from the same release as the engine, so `worker-standalone-app:2.6.1` is the
worker that went out with engine 2.6.1. Nothing enforces it — the two talk over Kafka and an older
worker keeps working — but a version that matches is one fewer variable when a run surprises you.
:::

It does **not** run the engine, and the absence is the point. The engine under test runs in its own
process and talks to this one over Kafka exactly as it would to a real worker, so what a scenario
proves here is what would happen in a deployment. A worker that embedded the engine would be testing
itself.

## Writing a test with it

`Dist13TestWorkerScenariosTest` in `modules/workflow-dist-e2e` is the worked example: a real
orchestrator and this worker, over real PostgreSQL and Kafka, driven entirely by scenarios. The
shape is always the same — start a process with a `TEST_CONFIG` variable, then assert on what the
engine did:

```java
createProcess("dist-sim-saga", "sim-rollback", new Variable("TEST_CONFIG", """
        {
          "default": { "durationMs": 0 },
          "tasks": { "charge-card": { "outcome": "ERROR", "reason": "card declined" } }
        }
        """));

awaitProcessStatus("sim-rollback", "COMPENSATED", DEFAULT_TIMEOUT);

var steps = stepStatuses("sim-rollback");
assertThat(steps).containsEntry("reserve-seat", "COMPLETED");   // ran, then was undone
assertThat(steps).containsEntry("release-seat", "COMPLETED");   // its compensation
assertThat(steps).containsEntry("charge-card", "ERROR");
assertThat(steps).doesNotContainEntry("refund-card", "COMPLETED");  // nothing to undo
```

Two things about that suite are worth copying.

**Give it its own topic.** The definitions there name `"topic": "sim-work"` and the worker binds to
it. Two workers on one topic in different consumer groups both receive every task and both answer
it, which is a confusing way to spend an afternoon.

**Read both sides.** With `worker.persistence=jpa` pointed at the same database as the engine, one
query gets you what the engine saw and the next gets you what the worker was asked:

```sql
SELECT attempt_count FROM step_execution_entity WHERE ...   -- retries the engine spent
SELECT attempt, outcome FROM received_task WHERE ...        -- times the worker was handed the task
```

That pairing is what makes a retry test say something. Either number alone is a claim about one
half of a conversation.

Mind the off-by-one between them, which is not a bug in either: the engine's `attempt_count` counts
**retries**, so it is 0 for a step that succeeded first time; the worker's `attempt` counts
**deliveries**, so it is 1 for the same step. Two failures then a success reads as `attempt_count=2`
and `attempt=3`.

One wrinkle if your test shares a JVM (or a classpath) with the engine: the engine's
`EmbeddedModeAutoConfigurationExcluder` defaults to `embedded` + `memory` and strips the Cloud
Stream and JPA auto-configuration from **every** context started there — including the worker's,
which then has no `StreamBridge` and no `EntityManagerFactory`. Set `workflow.mode=kafka` and
`workflow.persistence=jpa` on the worker's context to keep it away. The shipped
`worker-standalone-app` needs neither, because the engine is not on its classpath at all.

## Writing the real thing

When you are done exploring and need a worker that actually does something, start from
`modules/sample-worker` — a hundred lines that get the reply path, the cancellation path and the
idempotency right. See [Implementing Workers](/guides/workers/).
