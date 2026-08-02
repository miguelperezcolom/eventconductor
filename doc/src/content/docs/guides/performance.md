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

On a developer machine (Apple M3 Max), PostgreSQL and Kafka in containers, two orchestrator pods,
at 40 process instances per second, over 6,000 transitions:

| | per transition |
|---|---|
| p50 | 7.7 ms |
| p95 | 11.7 ms |
| p99 | 14.1 ms |
| max | 23.5 ms |

Roughly a couple of milliseconds of that is broker round trips; the rest is the write and the
dispatch. Reproduce it with the harness in `modules/workflow-benchmark` — see its README — and read
the caveats there before quoting any number from it.

### What synchronous producer sends cost

The engine waits for the broker to acknowledge each publish before treating it as delivered,
because the transactional outbox is only transactional if a failed delivery can be detected — see
[Reliability](/guides/reliability/). That is a round trip per message on the relay thread, so it is
fair to ask what it costs. Measured both ways on the same machine:

| | p50 | p95 | p99 | max throughput |
|---|---|---|---|---|
| Synchronous (the default) | 7.7 ms | 11.7 ms | 14.1 ms | 93.2 PI/s |
| Asynchronous | 7.1 ms | 11.1 ms | 13.4 ms | 97.5 PI/s |

**0.6 ms per transition and 4.4% of peak throughput**, to stop silently losing messages whenever
the broker blinks. Turning it off is `spring.cloud.stream.kafka.default.producer.sync=false`, and
it should be a considered decision rather than a tuning reflex.

One oddity worth recording rather than explaining away: the synchronous run reports *fewer*
database commits per step (7.0 against 11.0 at 40 PI/s), and the gap nearly vanishes under
saturation (5.0 against 5.5). The likely reason is that `xact_commit` counts implicit transactions,
so it is measuring the relay's empty polls: a slower pass means fewer of them, and under saturation
there are no empty passes either way. That is a hypothesis consistent with both measurements, not a
verified finding.

## Two ways to measure this wrong

**Unpaced load.** Fire everything at once and the pipeline saturates; from then on the gap between
steps is time spent queueing behind the backlog. The same setup that reports **7.7 ms** paced at 40
PI/s reports **1,828 ms** unpaced — and 99.6 ms merely at 100 PI/s, which is close enough to
saturation on this machine to be mostly queueing already. That is a measure of how deep the queue
got, not of what a transition costs. Pace the load below saturation, or report the number as what
it is.

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
