---
title: Reliability
description: What happens when a pod, a node, the database or the broker goes away mid-flight — measured, not asserted.
---

An orchestrator's only real promise is that work it accepted will finish. Everything else — the
DSL, the UI, the throughput — is worth nothing if a process can quietly stop forever because a
message went missing during a thirty-second broker restart.

This page is what happened when we tried to break it on purpose, and what had to be fixed as a
result. The harness is in `modules/workflow-benchmark/k8s/reliability` and every number here is
reproducible with two commands.

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
| Conservation | 6,086 acknowledged, 6,084 present — **2 short** (see below) |
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

## What this found, and what it cost

The first run of this suite was not a clean sweep. It left **3,356 processes stopped forever** and
lost **71 outbox messages**, and finding out why is most of the value the exercise produced.

**Workers were dropping replies.** `StreamBridge.send` reports failure by returning `false`, and
every worker in the project ignored it — so a reply refused during the broker outage vanished, the
consumer committed the offset anyway, and the task was never reported as done. Use
[`WorkerReply`](/guides/workers/), which retries and then throws so Kafka redelivers the task. Your
handlers must be idempotent; they always had to be.

**The outbox marked messages `Sent` that the broker never took.** The relay delivers before marking
the row, which is the right order and bought nothing, because the send was asynchronous: the record
is buffered, `true` comes back, the row is marked. Producer sends are now synchronous by default —
the engine contributes that setting itself rather than trusting each application's YAML — and a
refused send throws, leaving the row `Pending`. During the verification run the outbox held 55,600
rows marked `Sent` against 55,601 messages on the topic: one *more* on the topic, which is
at-least-once working correctly. Never fewer.

**A step with no timeout is invisible, not merely un-timed-out.** The deadline scan is an index
range over the deadline column, so a step without one is never looked at again; if its dispatch or
its reply is lost, the process stops and nothing reports it. Two things changed: the
`eventconductor.steps.stalled` gauge counts live steps with no deadline that have waited too long,
and `workflow.default-step-timeout-ms` gives ACTION and RULE steps a fallback deadline that hands
them to the existing retry path. It is off by default, and never applied to USER_TASK, PROCESS or
WAIT_FOR_MESSAGE, whose waiting is unbounded on purpose.

After those fixes the same battery left **zero stuck processes**.

## The two that still went missing

Conservation was 2 short, and both were dead-lettered rather than silently dropped — they are on
the `dead-letter` topic with the reason attached, replayable. The reason was
`JpaSystemException: Unable to rollback against JDBC Connection`, thrown when PostgreSQL was
stopped mid-transaction: the rollback failed because the connection was gone, and the classifier
did not recognise that as retryable.

It does now — connection-level `SQLException`s are matched on SQLState (class `08`, class `53`,
and PostgreSQL's `57P01`–`57P03`) as well as by type. Being unable to roll back because the
database has gone away is the most retryable failure this engine can have.

That fix is covered by unit tests and has **not yet been re-verified on the cluster**. It is the
one claim on this page that is reasoned rather than measured, and it is written this way on
purpose.

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
