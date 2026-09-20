---
title: Performance
description: What the engine costs per transition, why process instances per second is the wrong unit, and how to measure both yourself.
---

Most workflow-engine benchmarks answer a question nobody has: *how many process instances per
second can the engine start when the work is a no-op?* In any real deployment the answer is bounded
by what your workers can get through, not by the engine — so that figure mostly describes the load
generator.

The question worth answering is narrower: **what does the engine add per transition?**

## The unit: a transition, not a process instance

A **transition** is one step advanced by the engine: an outbox write, a relay, a dispatch to a
worker, the worker's reply, and the resulting status change consumed to decide what happens next.
Everything the engine does, it does once per transition. So it is the unit in which the engine's
cost and the engine's capacity are the same measurement read in two directions — **milliseconds per
transition**, and **transitions per second**. (An *N*-step process is *N* transitions, of which
*N−1* have their cost directly measurable: the first step has no preceding step to time the gap
from.)

**`START` and `END` are transitions.** They run no worker, but the engine writes a step execution,
publishes it and decides what follows, exactly as it does for an `ACTION` — so the benchmark
definition used throughout this page, three worker steps between a `START` and an `END`, is **five
transitions per process**, not three. Worth knowing before carrying any figure here across to an
engine that counts its unit differently.

Process instances per second is not a property of the engine, and this page reports it only with
the caveat attached. Three reasons:

- **It scales with the definition, not the engine.** A twelve-step saga reports a quarter of the
  PI/s a three-step definition does, with nothing about the engine having changed.
- **Waiting breaks it entirely.** A `USER_TASK` that sits with a human for three days, or a `TIMER`
  that waits until month-end, costs the engine two transitions while occupying a process instance
  for days. Any PI/s figure over a workload containing them is measuring the humans.
- **Over a mixed workload it is an average of unlike things.** A number aggregated over several
  definitions moves when the mix moves, which is a change in the load, not in the engine.

The harness therefore counts steps and processes out of the database rather than assuming either,
leads with transitions/s, and prints the measured steps-per-process next to any PI/s it reports. If
you see a PI/s figure anywhere without that ratio beside it — including in the comparisons other
engines publish — it is not a number you can carry across to your own workload.

## Engine cost per transition

Measure the gap between one step finishing and the next starting. Everything in that window is
engine work — writing the transition, publishing it, routing it, dispatching the next task — and
none of it is worker time, so unlike throughput it does not move when the workers get faster or
slower.

On a developer machine (Apple M3 Max), PostgreSQL and Kafka in containers, two orchestrator pods,
paced at **200 transitions per second** (40 process instances/s of the five-transition benchmark
definition), over 6,000 measured transitions:

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
| Synchronous (the default) | 7.7 ms | 11.7 ms | 14.1 ms | ~466 transitions/s |
| Asynchronous | 7.1 ms | 11.1 ms | 13.4 ms | ~488 transitions/s |

(Both drove the five-transition benchmark definition, so those are 93.2 and 97.5 process
instances/s — the same measurement in the unit that depends on the definition.)

**0.6 ms per transition and 4.4% of peak throughput**, to stop silently losing messages whenever
the broker blinks. Turning it off is `spring.cloud.stream.kafka.default.producer.sync=false`, and
it should be a considered decision rather than a tuning reflex.

One oddity worth recording rather than explaining away: the synchronous run reports *fewer*
database commits per transition (4.2 against 6.6 at 200 transitions/s), and the gap nearly vanishes
under saturation (3.0 against 3.3). The likely reason is that `xact_commit` counts implicit transactions,
so it is measuring the relay's empty polls: a slower pass means fewer of them, and under saturation
there are no empty passes either way. That is a hypothesis consistent with both measurements, not a
verified finding.

## Two ways to measure this wrong

**Unpaced load.** Fire everything at once and the pipeline saturates; from then on the gap between
steps is time spent queueing behind the backlog. The same setup that reports **7.7 ms** paced at 200
transitions/s reports **1,828 ms** unpaced — and 99.6 ms merely at 500 transitions/s, which is close
enough to saturation on this machine to be mostly queueing already. That is a measure of how deep the queue
got, not of what a transition costs. Pace the load below saturation, or report the number as what
it is.

**Everything on one machine.** Pods, broker, database and load generator sharing a laptop measure
the laptop. That is fine for comparing one build against another and it is not a scalability claim.
The harness prints this caveat itself on any run where the roles are not split across hosts.

## Where the ceiling actually is

Not the engine, in most deployments, and usually not the database either.

Sweeping the harness at 200 transitions/s **on a single machine**: PostgreSQL absorbed 40% more traffic for 7%
more throughput, so it was nowhere near its limit. Adding consumer threads and partitions made things
slightly *worse*. What moved the number was removing waiting — the outbox relay used to be found by a
poll, and at the old 500 ms default that alone was ~500 ms of every transition, since a transition
crosses the relay twice. It is now woken by the write.

:::note[The cluster figures below are in process instances per second]
They were measured before the harness reported transitions, driving the mixed benchmark suite
(`bench.workload=scale`: a saga, a linear definition, child processes, a fanout and a timer, with a
fraction made to fail and compensate). That suite's steps-per-process was never recorded, and the
cluster it ran on is gone — so these are **relabelled, not converted**. (Those definitions run 3 to
7 transitions each, so the figures are on the order of five times as many transitions; that is an
aid to reading them, not a measurement.) Picking a multiplier for a
mixed workload after the fact would be manufacturing the number, which is the habit this page
exists to break.

What the unit change does not touch is any comparison *among* them, because every one of those runs
drove the identical workload: the 2× from tuning is still a 2×, and two shards still absorb what one
could not. Read the absolute figures as "process instances/s of that suite" and the ratios as the
finding.
:::

That single-machine sweep understates two levers, though, because one laptop has no room to use them.
**On a multi-pod cluster, partitions × consumer threads is a first-order lever, not a rounding error.**
A process is pinned to a partition by its key, so the partition count caps how many processes can be in
flight at once; more pods and threads is more of them being *worked* rather than queued. Measured on a
dedicated-vCPU cluster driving a saturating load: raising `KAFKA_CONCURRENCY` 8→16 and partitions 48→96,
with the poll dropped to 50 ms, **roughly doubled** the sustained rate (~28 → ~56 PI/s of the mixed
suite) with CPU and disk still idle at the ceiling — the opposite of the laptop, where the same knobs only contend for the
same few cores. The poll interval matters here too, for a reason the single machine hides: the relay's
wake signal is *per-pod*, so a row written by one pod is picked up by another only on that other pod's
next poll — and across pods, that handoff is most of the traffic. (Pass it as a JVM `-D` system
property, not an env var: the dashed `workflow.outbox-poll-interval-ms` does not survive env-var
relaxed binding.)

**At that cluster ceiling, nothing is saturated.** Driving a single pipeline flat out on
dedicated-vCPU nodes, the sustained rate held around 90–110 PI/s of the mixed suite — on the order
of five times that in transitions — with PostgreSQL near a third of one
core, a handful of active queries against a connection pool sized in the hundreds, the outbox
drained, and Kafka idle. That is the signature of a *latency*-bound system, not a resource-bound
one. A transition is a chain of asynchronous round trips — the dispatch written to the outbox and
relayed to the worker, the worker's reply, and the resulting status change re-consumed from the
outbox to decide what happens next — and each hop is a poll cycle plus a network round trip: cheap
in CPU, costly in wall-clock. Those hops are not waste to remove; they are what makes a transition
durable, ordered, exactly-once and single-writer (see [Reliability](/guides/reliability/)). So
throughput is `in-flight concurrency ÷ per-transition latency`, and the only two levers that do not
fight that design are to put more transitions in flight, or to make each hop faster.

**More in flight, inside a pod.** A pod works one poll batch at a time and, by default, commits the
batch's processes one after another — so a pod's own parallelism is its partition count. Setting
`workflow.consumer.process-parallelism` above 1 works a batch's independent processes on a small
pool instead, so a pod can have more processes in flight than it owns partitions. It only ever runs
*distinct* processes concurrently — a single process's events share a partition, arrive in order and
land in one group that one thread drains, so ordering and single-writer are untouched — and each
concurrent process still commits in its own transaction, so keep the value at or below the
connection pool (`DB_POOL_SIZE`). It is a modest, situational lever: it earns its keep when a pod
owns few partitions relative to the work in each batch, and does little once partitions already
exceed the useful concurrency, where adding partitions and pods is the better spend. Default 1 is
the original behaviour.

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

This is measured, not just argued. On the same dedicated-vCPU cluster, holding the *total* compute
fixed and only varying how it is split, a single pipeline saturated around 90 PI/s of the mixed suite
while two shards absorbed everything the load generator could produce — past 115 — without saturating
at all. The ratio is the finding, and it is what the unit change leaves untouched.
The reason is exactly the ceiling above: a single pipeline is latency-bound, and a second shard is a
second independent pipeline — its own database, outbox relay, topics and consumer group — so it adds
in-flight capacity that tuning one pipeline cannot buy, no matter how idle its resources look.

Two properties make it practical rather than a rewrite:

- **Opt-in and config-only.** A shard is the stock engine re-pointed by config (`workflow.sharding.*`,
  off by default); a single-database deployment is unchanged and never touches any of it.
- **Elastic, without migration.** Shards are added and removed **hot**. Because a process is transient,
  rebalancing is by *draining* — a new shard takes new work, a removed one finishes what it holds and
  then leaves — so there is no reshard and no data copy. (See the elastic-sharding design in the
  benchmark module.)

So the ceiling is what you provision: one database's throughput, or N of them. What stays constant is
that the engine adds nothing on top — a claim the harness lets you check either way.

### The one part that did not scale: message correlation

Sharding scales transitions, but message delivery had a piece that did not. A `MessageReceived`
carries a name and a correlation key, not a shard, so the shard that can answer it is not derivable
from the sender; the message was **broadcast** to every shard, and each shard ran an indexed
correlation query against its own waiting steps to find out. With **S** shards and **M** messages,
that is **S × M** queries — and the per-shard cost is the *full* message rate **M**, no matter how
many shards you add. Adding shards scaled the transitions but not the correlation load, so a
message-heavy workload kept the one query stream that would not divide.

[Message routing](/reference/configuration/#sharding-advanced-opt-in)
(`workflow.sharding.message-routing.enabled`, off by default) removes it: a message correlated by
the process **business key** — the default correlation — is sent only to the shard the placement
store already owns that key on, so it is one query, on one shard. Driving **M = 100,000** business-key
messages through the router and the placement store, counting the correlation queries each shard is
asked to run:

| shards | broadcast (routing off) | routed (routing on) |
|---|---|---|
| 2 | 100,000/shard — 200,000 total | ~50,000/shard — 100,000 total |
| 4 | 100,000/shard — 400,000 total | ~25,000/shard — 100,000 total |
| 8 | 100,000/shard — 800,000 total | ~12,500/shard — 100,000 total |

The total stays at **M** (each message reaches exactly one shard), and the per-shard query load is
**M / S** — it **halves every time the shard count doubles**, the same horizontal scaling the
transitions already had. Without routing it is flat at **M** per shard: adding shards never lightens
it. That per-shard query stream is database work that competes with the transition writes on the same
shard, so removing it is transition throughput a message-heavy shard gets back — the correlation load
stops being the part of the workload that a bigger fleet cannot help.

That table is the first of **three layers**, each targeting a different kind of key, and each now
measured. The busiest-shard query count for **M = 100,000** messages, across shard counts:

**Layer 2 — the subscription table**, for keys that are not the business key. An expression key, or a
business key the placement store does not own (a child process, whose key is `parent:<id>` and is
never placed), cannot be resolved by placement. As a step starts waiting it writes its
`(name, key) → shard` into a shared subscription table; the router looks the message up there and
sends it only to the shard(s) waiting for it:

| shards | broadcast (routing off) | routed via subscriptions |
|---|---|---|
| 2 | 100,000/shard — 200,000 total | ~50,000/shard — 100,000 total |
| 4 | 100,000/shard — 400,000 total | ~25,000/shard — 100,000 total |
| 8 | 100,000/shard — 800,000 total | ~12,500/shard — 100,000 total |

**Layer 3 — a per-shard Bloom filter**, the residual, for anything still broadcast. A message the
first two layers cannot place still goes to every shard — but each shard first checks an in-memory
Bloom filter over the `(name, key)` pairs *it* has waiting, and drops the message without a query
when the filter rules it out. It needs no shared store at all, so it recovers the same M/S per-shard
load even in pure broadcast mode:

| shards | broadcast (no filter) | broadcast + filter |
|---|---|---|
| 2 | 100,000/shard — 200,000 total | ~50,000/shard — 100,000 total |
| 4 | 100,000/shard — 400,000 total | ~25,000/shard — 100,000 total |
| 8 | 100,000/shard — 800,000 total | ~12,500/shard — 100,000 total |

The two ways a Bloom filter can be wrong are not the same, and only one of them would matter here. A
**false negative** — saying "not waiting" when a step *is* — would drop a message that had a home, and
a Bloom filter is defined by never doing it: it never answers "absent" for a pair that was added, so a
waiting step is always queried. That is the property the routing rests on, and it is asserted directly
in the benchmark over thousands of pairs. A **false positive** — saying "maybe" when nothing is
waiting — is the only error it can make, and it is *not a lost message*: the shard runs the correlation
query, finds no waiter, and does nothing, which is exactly what a broadcast without the filter does for
that same message. It costs one query and changes no outcome. So the filter can only ever save queries,
never mis-route or drop; a false positive is "looked and found nothing", and the failure that would
break delivery — "did not look, and something was there" — cannot happen.

That is why the overhead is only a handful of extra queries: at this load **24 in 100,000 (0.02%)**, a
fraction of the 1% the filter is sized for (each shard runs at half load here, so its real rate is well
under the target). The rate is tunable down with
`workflow.sharding.message-routing.filter.false-positive-rate`, but never to zero — a zero-false-positive
structure is an exact set of every waiting pair, the unbounded memory the filter exists to avoid. Across
all three layers the total stays at **M** (each message is queried on essentially only the shards that
can match it) and the per-shard load is **M / S** — it **halves every time the shard count doubles**, the
same horizontal scaling the transitions already had. Without routing it is flat at **M** per shard:
adding shards never lightens it.

The router only ever *filters*: layers 1 and 2 send a message to a subset of shards, layer 3 drops it
on shards that cannot match, and anything unresolved still broadcasts — so a wrong or unavailable
answer costs a query, never a lost message. (Numbers from `MessageRoutingScalingBenchmarkTest`, which
drives real messages through the real router, placement store, subscription table and filter on H2.)

These tables count **queries**, the quantity the design targets and the one that decides whether the
correlation load divides with the fleet. They are not a wall-clock transitions/s run: that per-shard
query stream is database work competing with the transition writes on the same shard, so removing it
is throughput a message-heavy shard gets back, but the *rate* it gets back only shows up when the
shards do not share hardware. On a single machine the shards contend for the same CPU and disk (as the
two-shard note above), so the wall-clock figure comes from the message workload run on the multi-node
scale cluster (`bench.workload`, 2/4/8 shards, routing flag on and off — see the benchmark README); the
query scaling here is what says the figure will divide rather than stay flat.

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
