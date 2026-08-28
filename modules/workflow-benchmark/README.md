# Benchmark harness

Measures what the engine costs, against PostgreSQL and Kafka **you** provide.

Not a test and not run by CI. Tests assert; this produces numbers, and a number without its
conditions beside it gets quoted out of context — which is what happened to the throughput figure
in the comparison docs, measured on a laptop running everything at once and then repeated as
though it said something about scale.

## Running it

```bash
docker compose -f modules/workflow-benchmark/docker-compose.yml up -d
mvn -q -Pbenchmark -pl modules/workflow-benchmark -am -DskipTests install
mvn -q -Pbenchmark -pl modules/workflow-benchmark exec:java -Dbench.processes=600 -Dbench.rate=40
```

Every knob is a `-Dbench.*` property and the report prints all of them back:

| property | default | what it is |
|---|---|---|
| `bench.processes` | 2000 | instances to run (3 worker steps each, so 5 transitions with START and END) |
| `bench.rate` | 100 | **arrival rate per second; 0 = unpaced.** See below — this decides what you are measuring |
| `bench.pods` | 2 | orchestrator pods |
| `bench.worker.think-ms` | 0 | simulated work per task |
| `bench.worker.concurrency` | 16 | worker consumer threads |
| `bench.consumer.concurrency` | 3 | orchestrator consumer threads per binding |
| `bench.outbox.poll-ms` | 20 | relay poll interval |
| `bench.outbox.batch-size` | 100 | relay batch |
| `bench.pool-size` | 20 | JDBC pool per pod |
| `bench.jdbc.url` / `.user` / `.password` | localhost:55432 | where the database is |
| `bench.kafka.brokers` | localhost:59092 | where the broker is |

## The two questions, and why you cannot ask both at once

**"What does the engine cost per transition?"** — measured as the gap between one step finishing
and the next starting. Nothing but engine work happens in that window: writing the transition,
publishing it, routing it, picking it up, dispatching the next task. No worker time at all, so it
does not move when the workers get faster or slower.

That number only means something **below saturation**. Fire everything at once and the gap becomes
time spent queueing behind the backlog — a measure of how deep the queue got, not of what a
transition costs. So pace the load (`-Dbench.rate=N`, comfortably under the throughput you
measured) and the report says `ENGINE COST PER TRANSITION`. Leave it unpaced and it says
`TRANSITION LATENCY UNDER SATURATION` and tells you not to read it as cost.

**"How much can it get through?"** — run unpaced (`-Dbench.rate=0`) and read the throughput,
which the report gives in **transitions per second**. In any real deployment this is bounded by
what the workers can do, not by the engine: set `-Dbench.worker.think-ms=50` and watch it collapse
to the workers' capacity while the engine cost per transition does not move. That is the point. A
throughput figure mostly describes the load generator, which is why the report puts it second.

## The unit is a transition, not a process instance

A **transition** is one step advanced by the engine: an outbox write, a relay, a dispatch, the
worker's reply, the resulting status change consumed to decide what happens next. Everything the
engine does, it does once per transition — so it is the only unit in which the engine's cost and
the engine's capacity are the same measurement read two ways.

`START` and `END` are transitions: no worker runs, but the engine still writes a step execution,
publishes it and decides what follows. The default definition here is three worker steps between a
`START` and an `END`, so it is **five transitions per process**, and the report says so on its
steps-per-process line rather than leaving you to assume three.

Process instances per second is not a property of the engine, and the report says so where it
prints it. The same engine running a twelve-step saga instead of a three-step definition reports a
quarter of the PI/s with nothing having changed, and a definition that waits on a human or a timer
has no meaningful figure at all: a process can sit in flight for three days and cost the engine two
transitions. The report therefore counts steps and processes from the database rather than assuming
either, prints the measured steps-per-process next to the PI/s, and leads with transitions/s.

If you quote a PI/s number anywhere, quote the steps-per-process beside it or it says nothing.

## Across hosts

Everything above runs the pods, the workers and the load in one JVM, which measures a machine.
For a figure worth putting next to somebody else's, split the roles with `-Dbench.role` and put
them on separate hosts:

| role | what it does |
|---|---|
| `all` (default) | pods, workers and driver in this JVM — comparing builds on one box |
| `pods` | orchestrators only; stays up until stopped |
| `worker` | workers only; stays up until stopped |
| `drive` | publishes the load and measures, against pods running elsewhere |

The driver talks to the broker directly and never starts an engine, so it adds nothing to the
host under test. It waits for a pod to have imported the definition before starting, so **start
the pods first**.

One image runs all three, which matters more than it sounds: the same artifact everywhere is what
stops a multi-host run from quietly comparing two different builds.

```bash
docker build -f modules/workflow-benchmark/Dockerfile -t eventconductor-bench .

# on the pods host
docker run -e BENCH_OPTS="-Dbench.role=pods -Dbench.pods=4 \
  -Dbench.jdbc.url=jdbc:postgresql://db-host:5432/eventconductor \
  -Dbench.kafka.brokers=kafka-host:9092" eventconductor-bench

# on the workers host
docker run -e BENCH_OPTS="-Dbench.role=worker -Dbench.worker.think-ms=25 \
  -Dbench.kafka.brokers=kafka-host:9092" eventconductor-bench

# on the driver host
docker run -e BENCH_OPTS="-Dbench.role=drive -Dbench.processes=20000 -Dbench.rate=200 \
  -Dbench.jdbc.url=jdbc:postgresql://db-host:5432/eventconductor \
  -Dbench.kafka.brokers=kafka-host:9092" eventconductor-bench
```

`docker-compose.dist.yml` wires the same three services for a single machine, which is worth
running once to check the plumbing before booking hardware. It is not a measurement.

### What to record alongside the number

The report prints its own tuning line; publish it verbatim. Add what it cannot know:

- the host for each role — CPU, memory, and whether any two shared a machine
- the PostgreSQL and Kafka versions and any non-default settings
- network between the hosts, since two of the ~10 ms are broker round trips
- the arrival rate **and** the saturation throughput, so a reader can see the utilisation the
  latency was measured at
- which workload drove it (`bench.workload`), because the steps-per-process the report prints is an
  average over whatever mix that workload creates

A latency figure without its utilisation is not comparable to anyone else's, and that is the most
common way these numbers get quoted wrongly — including by us, before this module existed.

## What a measurement here can and cannot support

It supports: *this change made a transition cheaper than that change*, on one machine, and *the
engine adds X ms per transition at this rate*.

It does not support anything about scale. The harness runs the pods in its own JVM, and unless
you point it elsewhere the broker and the database are on the same box; the report says so at the
bottom of every local run. For a figure worth putting next to somebody else's, put the pods, the
broker, the database and the load generator on separate hosts, say so alongside the number, and
publish the tuning line the report prints.

## A worked example

Latency at 200 transitions/s (40 instances/s of the benchmark definition — three ACTION steps
between START and END, so five transitions), sweeping only the relay poll interval:

| `bench.outbox.poll-ms` | p50 | p95 |
|---|---|---|
| 20 | 27,1 ms | 37,8 ms |
| 5 | 13,9 ms | 20,7 ms |
| 1 | 11,7 ms | 19,1 ms |

Roughly half the per-transition cost at a 20 ms poll was *waiting for the poll*, and the floor
around 12 ms is the broker hops and the writes.

That finding is what led to waking the relay on write instead of polling for it. Re-measured
after that change, the poll interval barely registers:

| `bench.outbox.poll-ms` | before the signal | after |
|---|---|---|
| 500 (the shipped default) | 508,8 ms | **10,1 ms** |
| 20 | 28,6 ms | **10,7 ms** |

Which is the harness doing its job: a number nobody had measured, an attribution, a change, and
the same measurement again.
