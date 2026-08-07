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

Sweeping the harness at 40 PI/s **on a single machine**: PostgreSQL absorbed 40% more traffic for 7%
more throughput, so it was nowhere near its limit. Adding consumer threads and partitions made things
slightly *worse*. What moved the number was removing waiting — the outbox relay used to be found by a
poll, and at the old 500 ms default that alone was ~500 ms of every transition, since a transition
crosses the relay twice. It is now woken by the write.

That single-machine sweep understates two levers, though, because one laptop has no room to use them.
**On a multi-pod cluster, partitions × consumer threads is a first-order lever, not a rounding error.**
A process is pinned to a partition by its key, so the partition count caps how many processes can be in
flight at once; more pods and threads is more of them being *worked* rather than queued. Measured on a
dedicated-vCPU cluster driving a saturating load: raising `KAFKA_CONCURRENCY` 8→16 and partitions 48→96,
with the poll dropped to 50 ms, **roughly doubled** the sustained rate (~28 → ~56 PI/s) with CPU and
disk still idle at the ceiling — the opposite of the laptop, where the same knobs only contend for the
same few cores. The poll interval matters here too, for a reason the single machine hides: the relay's
wake signal is *per-pod*, so a row written by one pod is picked up by another only on that other pod's
next poll — and across pods, that handoff is most of the traffic. (Pass it as a JVM `-D` system
property, not an env var: the dashed `workflow.outbox-poll-interval-ms` does not survive env-var
relaxed binding.)

The practical order for tuning, once your workers are not the bottleneck:

1. **Run the migrations.** A schema built by `ddl-auto` alone has no indexes at all and every scan
   becomes sequential — see [Deployment Modes](/guides/deployment-modes/). This is worth more than
   every other item here combined.
2. **One consumer group per binding**, or Kafka leaves partitions unconsumed and processes stall
   while pods sit idle.
3. **Add pods, partitions and consumer threads together.** Events are keyed by process, so partitions
   spread the work and the relay drains from every pod — but only up to the partition count, so raise
   the three in step. On one machine this does little (nothing to parallelise onto); on a cluster it is
   the main lever, as above.
4. **Shorten the poll interval** once handoffs cross pods, and only then move batch sizes.

Past a single pipeline's ceiling — a process is pinned to one partition for ordering, so one
outbox→Kafka→worker→reply pipeline is finite however you tune it — the only lever left is horizontal:
run more of them. That is [sharding](#scaling-past-one-database), below.

## Scaling past one database

By default EventConductor keeps its state in one shared relational database, and the write ceiling is
**your database's, not the engine's**: the engine adds no ceiling of its own above what your database
and your workers can do — and you can verify that on your own hardware, in an afternoon, with the
harness in this repository. Numbers you can reproduce are worth more than numbers you cannot.

For a long time that single database was also the hard limit — the honest thing to concede against
Zeebe (a partitioned log, no database in the hot path) and Temporal (storage sharded horizontally).
**It no longer is.** [Sharding](/reference/configuration/#sharding-advanced-opt-in) runs the engine as
**N shared-nothing shards**, each a full stack with its own database, so writes scale horizontally with
the shard count — the same shard-your-storage principle Temporal uses. It falls out of the design
almost for free: a process is keyed to a partition and lives entirely on one shard, so shards never
coordinate.

Two properties make it practical rather than a rewrite:

- **Opt-in and config-only.** A shard is the stock engine re-pointed by config (`workflow.sharding.*`,
  off by default); a single-database deployment is unchanged and never touches any of it.
- **Elastic, without migration.** Shards are added and removed **hot**. Because a process is transient,
  rebalancing is by *draining* — a new shard takes new work, a removed one finishes what it holds and
  then leaves — so there is no reshard and no data copy. (See the elastic-sharding design in the
  benchmark module.)

So the ceiling is what you provision: one database's throughput, or N of them. What stays constant is
that the engine adds nothing on top — a claim the harness lets you check either way.

## Absorbing spikes

That ceiling is a *sustained* rate. Bursty load behaves better than the raw number suggests, because
every ingress — process creation, worker tasks, domain events — rides a durable Kafka log, so **arrival
rate is decoupled from processing rate**. A spike above the ceiling is absorbed as backlog and drained
at the engine's steady rate; the cost is latency, not lost or refused work. The throughput sweeps show
it directly: driving well past the sustained rate grew the queue and it drained flat-out afterwards —
nothing fell over, nothing was rejected.

Two honest bounds. It smooths *transient* peaks; it does not raise the sustained ceiling — a load that
stays above capacity grows the backlog without end (that is what sharding is for). And Kafka's retention
bounds how large the backlog can get. Within those, a Black Friday or month-end burst queues durably and
clears, instead of pushing back on the caller.

This comes with the topology rather than being a feature to enable: the buffer *is* the ingress bus. You
could put a durable queue in front of a dedicated cluster and get the same absorption — the difference is
that here it is there by construction, not a layer you design and operate.
