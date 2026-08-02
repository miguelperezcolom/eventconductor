---
title: Performance
description: What the engine costs per transition, why throughput is the wrong question, and how to measure both yourself.
---

Most workflow-engine benchmarks answer a question nobody has: *how many process instances per
second can the engine start when the work is a no-op?* In any real deployment the answer is bounded
by what your workers can get through, not by the engine — so that figure mostly describes the load
generator.

The question worth answering is narrower: **what does the engine add per transition?**

## Engine cost per transition

Measure the gap between one step finishing and the next starting. Everything in that window is
engine work — writing the transition, publishing it, routing it, dispatching the next task — and
none of it is worker time, so unlike throughput it does not move when the workers get faster or
slower.

On a developer machine, PostgreSQL and Kafka in containers, two orchestrator pods, at 40 process
instances per second:

| | per transition |
|---|---|
| p50 | ~10 ms |
| p95 | ~17 ms |
| p99 | ~21 ms |

Roughly a couple of milliseconds of that is broker round trips; the rest is the write and the
dispatch. Reproduce it with the harness in `modules/workflow-benchmark` — see its README — and read
the caveats there before quoting any number from it.

## Two ways to measure this wrong

**Unpaced load.** Fire everything at once and the pipeline saturates; from then on the gap between
steps is time spent queueing behind the backlog. The same setup that reports 27 ms paced reports
**1,284 ms** unpaced. That is a measure of how deep the queue got, not of what a transition costs.
Pace the load below saturation, or report the number as what it is.

**Everything on one machine.** Pods, broker, database and load generator sharing a laptop measure
the laptop. That is fine for comparing one build against another and it is not a scalability claim.
The harness prints this caveat itself on any run where the roles are not split across hosts.

## Where the ceiling actually is

Not the engine, in most deployments, and usually not the database either.

Sweeping the harness at 40 PI/s: PostgreSQL absorbed 40% more traffic for 7% more throughput, so it
was nowhere near its limit. Adding consumer threads and partitions made things slightly *worse*.
What moved the number was removing waiting — the outbox relay used to be found by a poll, and at the
old 500 ms default that alone was ~500 ms of every transition, since a transition crosses the relay
twice. It is now woken by the write.

The practical order for tuning, once your workers are not the bottleneck:

1. **Run the migrations.** A schema built by `ddl-auto` alone has no indexes at all and every scan
   becomes sequential — see [Deployment Modes](/guides/deployment-modes/). This is worth more than
   every other item here combined.
2. **One consumer group per binding**, or Kafka leaves partitions unconsumed and processes stall
   while pods sit idle.
3. **Add pods.** Events are keyed by process, so partitions spread the work and the relay drains
   from every pod.
4. Only then start moving poll intervals and batch sizes.

## What we do not claim

EventConductor keeps its state in a shared relational database. Camunda 8 (Zeebe) keeps it in a
partitioned log with local state and no database in the hot path; Temporal shards its storage
horizontally. **Both will out-scale a single database, and no amount of tuning here changes that.**

What the design buys instead is that the engine adds no ceiling of its own above what your database
and your workers can do — and that you can verify that claim on your own hardware, in an afternoon,
with the harness in this repository. Numbers you can reproduce are worth more than numbers you
cannot.
