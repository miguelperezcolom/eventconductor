---
title: Reliability
description: What happens when a pod, a node, the database or the broker goes away mid-flight — measured, not asserted.
---

An orchestrator's only real promise is that work it accepted will finish. Everything else — the
DSL, the UI, the throughput — is worth nothing if a process can quietly stop forever because a
message went missing during a thirty-second broker restart.

This page is what happens when we try to break it on purpose, measured on the current version. The
harness is in `modules/workflow-benchmark/k8s/reliability` and every number here is reproducible
with two commands.

## The invariants

Four things must hold, and the harness computes all of them from the engine's own tables:

| | |
|---|---|
| **Conservation** | processes in the engine = creation events the broker acknowledged |
| **Exactly-once** | no `(process, step)` pair has more than one execution row |
| **Drain** | once the load stops: no live process, no live step, no unsent outbox row |
| **No poison** | no outbox row parked as `Error` |

The count of work genuinely handed over is written to the database by the load driver once a
second, with `acks=all` and an idempotent producer. That matters: the driver is inside the blast
radius, so a verdict held in its memory would be worthless the first time a scenario killed it.

## The scenarios

Each runs while the engine is under continuous load, on a three-node Kubernetes cluster:

- one orchestrator pod killed
- the entire orchestrator tier killed at once
- a rolling redeploy under load
- the broker stopped for 90 seconds
- PostgreSQL stopped for 90 seconds
- the node hosting most of the engine drained
- **the workflow definition replaced while thousands of processes are mid-flight**

```bash
export BENCH_IMAGE=<user>/eventconductor-bench:<tag>
export SOAK_RATE=4
./ec-reliability.sh deploy
./chaos.sh all
./ec-reliability.sh drain 2400
```

## Results

All seven scenarios recovered, and after the load stopped the engine **drained completely in
144 seconds**: 6,084 processes, all finished, nothing live, nothing unsent.

| Invariant | Result |
|---|---|
| Conservation | 6,086 acknowledged, 6,084 present — **2 dead-lettered** (see below) |
| Exactly-once | **0** duplicate step executions |
| Drain | **0** live processes, **0** live steps, **0** unsent outbox rows |
| No poison | **0** outbox rows in `Error` |

Recovery is defined as the finished count rising across two consecutive samples — not as pods
reporting Ready, which is a weaker claim: a pod can be Ready and consuming nothing.

| Scenario | Progress resumed |
|---|---|
| One pod killed | 54 s |
| Whole tier killed | 53 s |
| Rolling redeploy | 12 s |
| Broker down 90 s | 23 s after it returned |
| PostgreSQL down 90 s | 48 s after it returned |
| Node drained | 60 s |
| Definition replaced | 12 s |

### Changing a workflow definition under load

The design claims a process runs against the definition it was created with, copied into its own
row, so editing a definition cannot corrupt work already in flight. Replacing a three-step
definition with a four-step one while thousands of processes were running produced exactly two
shapes and no third: 5,662 processes finished with the old definition and 422 with the new one.
No hybrids, in either run of this test.

## Why work is not lost

Three mechanisms carry the guarantees above through an outage. Each is engine default behaviour on
the current version.

**Workers never drop a reply.** `StreamBridge.send` reports failure by returning `false`. Replies go
through [`WorkerReply`](/guides/workers/), which retries and then throws so Kafka redelivers the
task rather than committing an offset for work that was never reported as done. A reply refused
during a broker outage is redelivered once the broker returns. Your handlers must be idempotent.
That covers the engine's own workers too — the rule runtime answering a RULE step, and the forms
engine answering a USER_TASK. The human task is the one that can least afford a lost reply: it
arrives over HTTP, so no offset redelivers it, and a USER_TASK is deliberately given no fallback
deadline. There the reply goes out *before* the task is marked complete, so a refused one leaves
the task open for the person to submit again rather than stranding the process.

**The outbox only marks a message `Sent` once the broker has it.** The relay delivers before marking
the row, and producer sends are synchronous by default — the framework contributes that setting
itself, to every module that can publish, rather than trusting each application's YAML — so a refused send throws and leaves the row
`Pending` to be retried. During the verification run the outbox held 55,600 rows marked `Sent`
against 55,601 messages on the topic: one *more* on the topic, which is at-least-once working
correctly. Never fewer.

**No live step is invisible.** The deadline scan is an index range over the deadline column, so a
step without a deadline would never be looked at again. The `eventconductor.steps.stalled` gauge
counts live steps with no deadline that have waited too long, and `workflow.default-step-timeout-ms`
gives ACTION and RULE steps a fallback deadline that hands them to the existing retry path. The
fallback is off by default, and never applied to USER_TASK, PROCESS or WAIT_FOR_MESSAGE, whose
waiting is unbounded on purpose.

## The two that were dead-lettered

Conservation was 2 short, and both were dead-lettered rather than silently dropped — they are on
the `dead-letter` topic with the reason attached, replayable. The reason was
`JpaSystemException: Unable to rollback against JDBC Connection`, thrown when PostgreSQL was
stopped mid-transaction: the rollback fails because the connection is gone.

Connection-level `SQLException`s are classified as retryable — matched on SQLState (class `08`,
class `53`, and PostgreSQL's `57P01`–`57P03`) as well as by type. Being unable to roll back because
the database has gone away is the most retryable failure this engine can have.

## A record the engine cannot read

The section above is about an event the engine understood and could not process. There is an
earlier failure: bytes that never become an event at all — JSON that does not parse, or a `type`
this version does not know.

It reaches no handler, so none of the handling above applies to it. Spring Cloud Stream's converter
fails, and the binder's default answer is to drop the record, commit the batch and advance the
offset. Until 2.2.2 that is all that happened: no log line at any level, no dead letter, no metric,
and consumer-group lag back to zero. A producer sending a thousand malformed messages saw a healthy
engine that had created nothing, which reads exactly like messages that never arrived — so the
search starts at the producer, then the topic, then the consumer group, and the payload is the last
thing anyone looks at.

Such a record is now logged at ERROR with the reason and an excerpt of the payload, parked on the
`dead-letter` topic as the **original bytes** with `x-dead-letter-unreadable: true`, and counted by
`eventconductor.events.dead.lettered` like any other dead letter.

It is still skipped rather than retried, and deliberately: bytes that cannot be parsed now cannot
be parsed on redelivery either, so failing the batch would stall the partition for every process
behind it, for ever. What changed is that the skip says so.

## Sharded message routing cannot lose a message

[Message routing](/guides/performance/#the-one-part-that-did-not-scale-message-correlation) sends a
message to the shard that can correlate it instead of broadcasting it to all of them. The reliability
question is whether narrowing the fan-out can drop a message the broadcast would have delivered. It
cannot, by two properties, both of which hold with routing on:

- **The router only filters; it never decides.** Every layer fails toward the broadcast that always
  worked: a message whose key the placement store does not own, or an expression key no shard has
  subscribed, is broadcast; and if a routing store is **down**, the lookup is caught and the message
  is broadcast too. A wrong or unavailable answer costs an extra query on a shard that will not match,
  never a lost message — the receiving shard still runs the ordinary correlation, so routing changes
  *where* a message is tried, not *whether* it is.
- **The per-shard filter can only ever save a query.** The residual layer — a Bloom filter each shard
  keeps over the pairs it has waiting — is asked, of a broadcast message, "could a step here match?"
  A Bloom filter never answers "no" for a pair it holds, so a waiting step is never filtered away; its
  only possible error is the opposite one, saying "maybe" when nothing waits, which costs a query that
  finds nothing and drops no message. The failure that would lose a message — "did not look, and
  something was there" — cannot occur.
- **A routed message is as durable as any other.** It rides the target shard's `upstream` Kafka topic,
  so a shard that is down when a message is routed to it correlates the message when it returns — the
  same recovery the broker-outage scenario already measures.

Three chaos scenarios confirm it. Re-running the **broker-down** scenario (`Dist06`) with routing
present showed recovery unchanged: the outage is ridden and progress resumes when the broker returns,
exactly as with routing off. `Dist21` then puts the message path itself through an outage with the
filter on: 20 processes are driven to a `WAIT_FOR_MESSAGE` and parked, the broker is stopped, each
process's resume message is published into the dead broker, and while it is down nothing advances; when
the broker returns **all 20 correlate their resume and complete, the outbox drains to zero, and nothing
is dead-lettered** — no resume is lost across the outage, and the filter on the correlation path drops
none of them.

`Dist22` runs the same outage across **two genuinely separate shards** — each a full engine with its own
database and its own `upstream`/`outbox` topics, sharing the one `messages` topic every shard consumes.
Twelve processes are split across the shards and parked; the broker is stopped; each resume is broadcast
onto the shared topic; and when the broker returns **both shards receive every resume, each completes
exactly its own six, both outboxes drain to zero, and nothing is dead-lettered**. It is the cross-shard
properties the single-node tests cannot reach, shown to survive an outage: the shared-topic fan-out, the
per-shard filter ruling out the keys a shard does not hold, and recovery of the message path itself.

The subscription layer carries one semantic to know — a *projection window*: a step that has started
waiting but whose subscription has not projected yet is not yet visible to that layer, so it is still
covered by the business-key and broadcast layers, and a `WAIT_FOR_MESSAGE` that cannot tolerate the
window can opt out of routing to force broadcast.

## Restarting the engine

Restart an orchestrator — a redeploy, a crashed pod, a drained node, the whole tier at once — and
every process carries on from where it was. Nothing is replayed from the beginning and nothing is
lost, because the engine keeps no state that matters in memory:

- **Every transition is in the database**, committed together with the events it produces (the
  outbox). A pod that dies between the two leaves the events `Pending`, and the relay sends them
  when a pod comes back — at-least-once, as always.
- **Waits are rows, not threads.** A TIMER's due moment, a step's timeout or deadline, and a
  WAIT_FOR_MESSAGE's subscription are stored on the step, and the scheduler finds them with an
  indexed query. A timer set for three days fires on time whichever pod is up then, and a message
  that arrives after the restart still finds the step waiting for it. (A step that started under an
  engine version older than those columns is armed once at startup by `InFlightStepRearmRunner`,
  which retries in the background until the database answers.)
- **Kafka remembers where each consumer was.** Worker replies sent while the engine was down are
  consumed when it returns; with several orchestrators, the survivors take over the dead pod's
  partitions at once.
- **The database may come back after the pod.** With a lazy connection pool, `ddl-auto: none` and
  an explicit Hibernate dialect, an orchestrator starts with PostgreSQL unavailable and picks up its
  work when the database returns — the recipe is DIST-08 in `TESTING.md`. Without them it fails to
  start, and Kubernetes restarts it until the database answers.

Three things to know:

- **A task running inside the pod that died** — an embedded worker, in `embedded` mode — is not
  redelivered: the step stays `RUNNING` until its `timeout`, and then retries or takes its
  `onTimeoutStepId`. Give ACTION steps a `timeout`, or set `workflow.default-step-timeout-ms`.
  Tasks on Kafka workers are unaffected: the task is still on its topic, or still being worked on.
- **`workflow.persistence=memory` keeps nothing** across a restart. It is for tests and demos.
- **The database has to be durable.** Everything above is only as good as the storage under
  PostgreSQL: on an ephemeral volume (a Kubernetes `emptyDir`, a container without a volume),
  restarting the *database* pod loses every process, however durable the engine is.

These are the scenarios the [distributed suite](https://github.com/miguelperezcolom/eventconductor/blob/main/TESTING.md)
breaks the engine with on every build: a pod killed mid-process (DIST-02), the broker stopped
mid-process (DIST-06), a pod started without PostgreSQL (DIST-08), and a node dying inside an
embedded worker (`InlineCrashRecoveryE2eTest`).

## What to watch in production

| | |
|---|---|
| `eventconductor.steps.stalled` | Live steps with no deadline that nothing will time out. Any sustained non-zero value is work that will never finish. |
| `eventconductor.events.dead.lettered` | The engine gave up on a specific event and said so. Always worth a look. |
| `eventconductor.process.concurrent.writes.rejected` | Optimistic-lock conflicts. Expected briefly during a rebalance, flat otherwise. |
| Outbox rows not `Sent` | Normal during a broker outage and should return to zero. A sustained backlog is not normal. |

## What is deliberately not tuned

`synchronous_commit` stays on in every measurement here. Turning it off would multiply throughput
and invalidate the whole page, because the losses it permits are precisely the ones being tested
for.
