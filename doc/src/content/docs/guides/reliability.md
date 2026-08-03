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
