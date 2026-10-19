---
title: Benchmarks (local)
description: A reproducible local benchmark of EventConductor — six scenarios in four deployment modes on one laptop, with the method, the environment, the results and what they do and do not support.
---

This page publishes one run of the **local benchmark suite** (`scripts/benchmark.sh`): six scenarios,
four deployment modes, three measured repetitions each, on one developer laptop. It exists for two
things:

- **comparing EventConductor with itself** — one mode against another, one version against the next,
  one setting against another, on the same machine;
- giving you an **order of magnitude** before you measure on your own hardware.

It is **not** a capacity claim and **not** a comparison with Temporal, Camunda or anyone else. Every
component — engine pods, workers, load generator, PostgreSQL, Redpanda — runs on the same laptop,
alongside whatever else the laptop was doing (recorded below). For what a figure from a real
deployment looks like and how to read one, see [Performance](/guides/performance/); for how the
engines compare architecturally, [Comparison](/guides/comparison/).

## What is measured

Every worker is a **no-op**: it answers at once, so what is left is the engine, its database and its
broker. Every ACTION step in kafka mode is a real round trip to a worker over the broker; in embedded
mode it is an in-process call.

| scenario | definition | measures |
|---|---|---|
| **sequential** | `START` → 5 × `ACTION` → `END` (7 step executions) | throughput, end-to-end latency |
| **saga** | `START` → 4 × `ACTION`, the 4th fails (`retries: 0`) → 3 compensations run → `COMPENSATED` (9 step executions) | throughput, end-to-end latency |
| **fan-out** | `START` → one `ACTION` with `forEach` over 50 items, `parallel`, `maxConcurrency: 10` → `END` (53) | processes/s, items/s, latency per process |
| **timers** | `START` → `TIMER` `PT1S` → `END`, 10,000 processes | how late the timer fires (p50/p95/p99), throughput |
| **messages** | `START` → `WAIT_FOR_MESSAGE` (correlated by business key) → `END`; 10,000 processes parked, then 10,000 messages | correlation throughput, message → process end |
| **sync** | `START` → `ACTION` → `REPLY` → `END`, invoked through `SyncInvocationService` (what the REST endpoint calls, minus HTTP) | request latency and requests/s at 1, 4 and 16 concurrent callers |

The definitions are in `modules/workflow-benchmark/src/main/resources/workflows/suite-*.json`.

The four **modes**:

| mode | engine | persistence | broker | how load enters |
|---|---|---|---|---|
| embedded / memory | 1 Spring context | in the heap | none | 8 caller threads call `ProcessUpstreamEventUseCase` |
| embedded / JPA | 1 Spring context | PostgreSQL | none | 8 caller threads, as above |
| kafka, 1 pod | 1 orchestrator context + 1 worker context | PostgreSQL | Redpanda | a Kafka producer on the `upstream` topic, as any external system |
| kafka, 2 pods | 2 orchestrator contexts + 1 worker context | PostgreSQL | Redpanda | as above |

## Method

- **Warm-up, then three repetitions.** Each scenario runs once at a reduced size and is discarded
  (JIT, connection pools, Kafka metadata), then three measured repetitions. Tables show the **median
  of the three**; throughput cells show `median (min–max)`. Every repetition is in the JSON.
- **Each repetition starts from the same state.** After a repetition the suite waits for the outbox
  to drain and deletes that repetition's processes and step executions (`VACUUM ANALYZE` on
  PostgreSQL), so repetition 3 does not run against twice the rows repetition 1 did. Each mode gets a
  fresh database and fresh topics.
- **Throughput and latency are separate phases.** Throughput: submit the whole batch at once (2,000
  sequential, 1,000 saga, 100 fan-out processes) and divide by the time from the first submission to
  the last process's `finished` timestamp. Latency: afterwards, a **paced** phase at **half the
  throughput just measured** (capped at 2,000/s) for 20 s. Latency measured under saturation is queue
  depth, not cost — [Performance](/guides/performance/#two-ways-to-measure-this-wrong) explains why.
- **Latency is measured from the intended send time** of each process in the paced schedule to the
  `finished` timestamp the engine recorded, so a stalled driver cannot hide a stall in the engine.
- **Timers and messages are bursts by design.** 10,000 processes are created at once; timer
  *lateness* is the engine's own `finishedAt − deadlineAt` of each `TIMER` step, so it is unaffected
  by when the process started. For messages, 10,000 are sent at once after all 10,000 processes are
  confirmed waiting; "message → end" is therefore mostly **time spent in the backlog**, and the
  number to read is the correlation throughput.
- **Sync is closed-loop**: 1, 4 or 16 threads each invoking, waiting for the reply and invoking again,
  for 15 s per level.
- **Resources** are sampled every 500 ms: this JVM's CPU (in cores) and heap, and the whole machine's
  CPU (which includes the containers, the Docker VM and everything else running). Phases shorter than
  a second have no sample (shown as `–`).
- **Steps per process are counted, not assumed** — from the step executions the engine wrote — and
  shown next to processes/s, because processes per second depends on the definition (see
  [Performance](/guides/performance/#the-unit-a-transition-not-a-process-instance)).

## Environment

| | |
|---|---|
| Machine | Apple M3 Max, 14 cores (10 performance + 4 efficiency), 36 GB RAM, macOS 15.6.1 |
| JDK | Amazon Corretto 21.0.8, `-Xms6g -Xmx6g -XX:+UseG1GC`, one JVM for every pod, worker and the driver |
| Docker | Docker Desktop 27.4.0, VM with 14 CPUs and 23 GB; **no per-container CPU or memory limits** |
| PostgreSQL | `postgres:16-alpine` (16.14), Testcontainers defaults: `max_connections=100`, `shared_buffers=128MB`, **`fsync=off`** (see [caveats](#caveats)) |
| Redpanda | `redpandadata/redpanda:v24.1.7`, Testcontainers defaults: `--mode=dev-container --smp=1 --memory=1G`, topics with 6 partitions |
| EventConductor | `main` at `ad129191` (2.31.0) plus the pull request that added this page (the JSON says `ad129191-dirty`); the memory-mode re-run includes that pull request's in-memory store fixes |
| Background load | load average ~7 at the start of both runs — the machine was shared with other builds, a local Kubernetes cluster and Spotlight indexing; per-repetition load average 3.5–19 in the main run and 4.9–25 in the memory-mode re-run. It is recorded per repetition in the JSON. |

Engine settings are the **shipped defaults** except where the suite needs a value to run at all:

| setting | value |
|---|---|
| JPA | `ddl-auto=update` + the engine's own Flyway migrations (indexes), Hibernate `jdbc.batch_size=20`, `order_inserts/updates=true` — as `apps/orchestrator-standalone-app` |
| Hikari pool | 10 per context |
| `workflow.outbox-poll-interval-ms` | 500 (default; the relay is also woken by its own pod's writes) |
| `workflow.outbox.batch-size` / `relay-concurrency` | 100 / 0 (defaults) |
| `workflow.timeout-scan-interval-ms` | **10,000 (default)** — decides timer lateness, see below |
| `workflow.embedded.worker-threads` | 0 (default: the worker runs on the calling thread) |
| `workflow.sync.inline.enabled` | true (default) |
| Kafka consumers | `batch-mode`, concurrency 3 per binding per pod; worker concurrency 6 |
| Kafka producers | the engine's default synchronous sends |

## Results

Medians of three repetitions; throughput as `median (min–max)`. Latency is end to end (submission to
the process's `finished` timestamp) in the **paced** phase. "Steps/s" is processes/s × the step
executions each process actually wrote. The memory-mode row is from a second run after the fixes
described [below](#problems-the-suite-found); every other row is from the main run. Raw JSON for every
run on this page: [`/benchmarks/2026-10-19/`](https://github.com/miguelperezcolom/eventconductor/tree/main/doc/public/benchmarks/2026-10-19).

### Sequential — `START` → 5 × `ACTION` → `END` (7 step executions)

| mode | processes/s | steps/s | paced at /s | p50 ms | p95 ms | p99 ms | JVM cores | machine CPU % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| embedded / memory | 12,514 (11,991–12,635) | 87,597 | 2,000 | 0.87 | 0.94 | 1.03 | – | – |
| embedded / JPA | 8.64 (8.26–9.91) | 60.5 | 4.32 | 106 | 142 | 176 | 0.18 | 42.7 |
| kafka, 1 pod | 41.0 (32.4–49.8) | 287 | 20.5 | 204 | 269 | 314 | 0.57 | 52.3 |
| kafka, 2 pods | 58.5 (51.6–61.3) | 410 | 29.3 | 170 | 265 | 390 | 0.95 | 80.0 |

### Saga — 4 steps, the last fails, 3 compensations (9 step executions)

| mode | processes/s | steps/s | paced at /s | p50 ms | p95 ms | p99 ms | JVM cores | machine CPU % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| embedded / memory | 7,366 (7,167–7,560) | 66,297 | 2,000 | 1.00 | 1.08 | 1.17 | – | – |
| embedded / JPA | 11.3 (11.1–12.1) | 102 | 5.66 | 90.0 | 217 | 251 | 0.21 | 29.2 |
| kafka, 1 pod | 36.9 (36.1–37.8) | 332 | 18.4 | 133 | 198 | 269 | 0.53 | 52.8 |
| kafka, 2 pods | 59.0 (59.0–62.0) | 531 | 29.5 | 456 | 856 | 1,042 | 1.08 | 77.6 |

Every saga process in every mode ended `COMPENSATED`.

### Fan-out — `forEach` over 50 items, parallel, `maxConcurrency: 10` (53 step executions)

| mode | processes/s | items/s | paced at /s | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|
| embedded / memory | 971 (855–975) | 48,543 (42,741–48,731) | 485 | 8.91 | 10.0 | 30.0 |
| embedded / JPA | 1.12 (1.10–1.15) | 56.0 (54.9–57.5) | 1.00 | 833 | 931 | 967 |
| kafka, 1 pod | **failed** — 19 of 100 processes stuck in the first repetition | | | | | |
| kafka, 2 pods | 7.22 (one repetition) | 361 | 3.61 | 1,927 | 4,025 | 4,929 |

The kafka fan-out runs lost events — see [Problems the suite found](#problems-the-suite-found). With
two pods one repetition completed and the second left 12 of 100 processes stuck, so its row is a
single repetition and should be read as such.

### Timers — 10,000 processes with a `TIMER` `PT1S`, default 10 s scan interval

| mode | processes/s | lateness p50 ms | p95 ms | p99 ms | max ms |
|---|---:|---:|---:|---:|---:|
| embedded / memory | 954 (954–956) | 9,075 | 9,347 | 9,411 | 9,470 |
| embedded / JPA | 27.0 (25.9–27.4) | 10,602 | 18,096 | 19,596 | 20,475 |
| kafka, 1 pod | 146 (140–158) | 9,259 | 15,458 | 17,702 | 18,687 |
| kafka, 2 pods | 186 (185–214) | 8,027 | 12,167 | 14,567 | 16,105 |

The same scenario with `workflow.timeout-scan-interval-ms=1000`
(`-Dsuite.engine.workflow.timeout-scan-interval-ms=1000`):

| mode | processes/s | lateness p50 ms | p95 ms | p99 ms | max ms |
|---|---:|---:|---:|---:|---:|
| embedded / memory | 4,134 (3,954–4,137) | 997 | 1,311 | 1,369 | 1,416 |
| kafka, 1 pod | 153 (135–161) | 1,255 | 14,381 | 17,287 | 18,966 |

With a 1 s scan the median timer fires about a second late in both modes; the kafka tail is the time
it takes one pod to work through 10,000 timers that all came due in the same second (~150
processes/s), not the timer itself.

### Messages — 10,000 parked processes, then 10,000 messages at once

| mode | correlation throughput (messages/s) | message → end p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| embedded / memory | 2,163 (2,037–2,205) | 5.61 | 7.26 | 9.09 |
| embedded / JPA | 30.9 (30.2–31.2) | 214,386 | 301,953 | 309,718 |
| kafka, 1 pod | 437 (436–447) | 10,843 | 20,477 | 22,363 |
| kafka, 2 pods | 574 (571–612) | 8,122 | 15,415 | 16,842 |

The latency columns are a 10,000-message backlog draining at the throughput on the left — in
embedded/JPA, five minutes of it. Read the throughput.

### Synchronous invocation — `START` → `ACTION` → `REPLY` → `END`, closed loop

| mode | c=1 req/s | p50 ms | p99 ms | c=4 req/s | p50 ms | p99 ms | c=16 req/s | p50 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| embedded / memory | 3,060 | 0.24 | 1.04 | 10,616 | 0.26 | 1.38 | 16,000 | 0.28 | 11.1 |
| embedded / JPA | 22.9 | 43.5 | 54.6 | 62.4 | 63.0 | 83.9 | 77.1 | 73.3 | 1,068 |
| kafka, 1 pod | 23.6 | 41.4 | 55.8 | 69.4 | 56.1 | 85.6 | 108 | 134 | 403 |
| kafka, 2 pods | 22.8 | 43.0 | 60.9 | 69.9 | 55.6 | 83.5 | 117 | 131 | 244 |

No request failed or timed out in any mode. Requests always enter through the first pod; with two
pods the process's partition is owned by the other pod about half the time.

### Durable commits make no measurable difference here

Sequential and sync, at a quarter of the sequential batch size, with PostgreSQL's `fsync` off (the
Testcontainers default used above) and on:

| | embedded / JPA processes/s | p50 ms | kafka, 1 pod processes/s | p50 ms | kafka sync c=1 p50 ms |
|---|---:|---:|---:|---:|---:|
| `fsync=off` | 9.85 | 122 | 41.5 | 194 | 42.4 |
| `fsync=on` | 10.0 | 130 | 39.1 | 202 | 42.4 |

On this machine the cost is round trips, not disk flushes (Docker Desktop's virtual disk is also not
a production disk). On real storage with slow `fsync` — network block storage in particular — expect
the JPA modes to be slower than this, as [Performance](/guides/performance/#where-the-ceiling-actually-is)
describes.

### Does it slow down as history grows?

Five consecutive batches of the sequential definition, never purged (`-Dsuite.scenarios=growth`):

| | batch 1 | batch 2 | batch 3 | batch 4 | batch 5 |
|---|---:|---:|---:|---:|---:|
| memory, 4,000 per batch, **before** the fixes | 1,178 | 389 | 173 | 78 | 52 |
| memory, 20,000 per batch, after | 9,648 | 9,964 | 10,047 | 8,929 | 10,444 |
| embedded / JPA, 400 per batch | 9.82 | 10.2 | 10.2 | 10.4 | 9.51 |

(processes/s, one repetition each.)

## Problems the suite found

Running the same scenarios in every mode surfaced five problems. Three were small and are fixed in
the pull request that added this page; two are not, and are described here for a follow-up.

**Fixed — memory mode got slower with every step it had ever run.** Every per-process query of the
in-memory step store (`findByProcessId`, `findPendingOrRunningByProcessId`, and the due-timer and
retry lookups by process) scanned the whole store, and several run on every transition — so the
cost of a transition grew with the history in the heap. Five consecutive batches of 4,000 sequential
processes, never purged: **1,178 → 389 → 173 → 78 → 52 processes/s** before. With that fixed (an id
index per process), the next scan showed up at larger sizes: `findByBusinessKey`, which every process
creation calls, also scanned the whole store — batches of 20,000 fell from ~5,000 to ~560/s. With
both indexed, five batches of 20,000 hold at **~10,000 processes/s** (table above). Memory mode is what the [test kit](/guides/testing-workflows/) runs on, so
a long test class paid for it too.

**Fixed — memory-mode synchronous invocations ran one at a time.** `InMemoryInvocationRepository`
created every invocation under one monitor, and in memory mode the creation runs the whole process
inline, so 1, 4 and 16 callers all got ~520 requests/s with one core busy. It now locks per
idempotency key (the JPA store's equivalent is a unique index, which never serialized different
keys). The same class also scanned every retained invocation on each `REPLY` step, so each call got
slower than the last (560 → 170 requests/s for one caller over three 45-second runs); it now indexes
invocations by process. Together with the business-key index: **3,060 / 10,616 / 16,000 requests/s**
at 1 / 4 / 16 callers, steady across repetitions.

**Fixed — the embedded/JPA outbox relay never stopped.** Its thread was a `while (true)`, so closing
the engine's Spring context left it polling a closed connection pool and logging an error every
500 ms for the life of the JVM — anything that starts more than one engine in a JVM (this suite,
tests that restart an engine) inherited it. It now stops with the context.

**Not fixed — kafka mode dropped events under a parallel `forEach`.** In both kafka modes the fan-out
scenario ended with processes stuck for good (81 of 100 completed in one pod, 88 of 100 in two),
iterations left `PENDING` with nothing left in the outbox. The log shows the cause: with ten
iterations of the same process completing at once, the pod's `consumeUpstream` and `consumeOutbox`
consumers write the same process concurrently, the optimistic lock rejects one
(`ConcurrentProcessAccessException`, plus PostgreSQL deadlocks), the engine rethrows it as retryable
so the binder will redeliver — and Spring Kafka's default batch error handler retries a batch **ten
times with no back-off and then discards it**: `FallbackBatchErrorHandler: Records discarded:
outbox-5@31836, …` — 383 records in two batches with one pod, 254 in one batch with two. The engine's
own comments assume an unbounded redelivery that the default container does not give. The fix — a
container error handler with back-off and no discard for retryable failures, and fewer same-process
write conflicts — changes failure semantics on the hot path, so it is left to its own change. Until
then: give fan-out ACTION steps a `timeout` (or set `workflow.default-step-timeout-ms`) so a lost
reply is retried rather than waited for. Sequential, saga, timer, message and sync scenarios lost
nothing in any mode.

**Not fixed, by design — embedded/JPA advances everything on one thread.** The embedded outbox relay
delivers each message by running the engine synchronously, one message at a time, on a single thread
([the class says why](https://github.com/miguelperezcolom/eventconductor/blob/main/modules/workflow-engine/src/main/java/io/mateu/workflow/infra/out/async/EmbeddedOutboxRelay.java)).
Sampled during the sequential scenario, that thread spent nearly all its time waiting for PostgreSQL
round trips, the JVM used **0.2 cores**, and PostgreSQL counted roughly 15 commits per step
execution (including the suite's own progress polling). So embedded/JPA throughput is the inverse of the round-trip latency × statements per step
— about **60–100 step executions/s** here, with the CPU idle. It is the mode to pick for durability
without a broker, not for throughput; making its dispatch concurrent is a separate change with its
own risks.

## What the numbers say

- **Memory mode measures the engine's own code path** — no I/O at all — at 65,000–90,000 step
  executions/s and a millisecond per sequential process. It is a ceiling for the logic, not for any
  durable deployment.
- **With a database in the loop the engine is latency-bound, not CPU-bound.** The JVM never used more
  than about one core in any JPA or kafka phase while the machine still had most of its 14 cores
  free. Throughput there is set by round trips (database and broker) per transition and by how many
  transitions are in flight — the same conclusion [Performance](/guides/performance/) reaches on a
  cluster.
- **A second pod helps throughput and costs cross-pod latency.** Two pods moved sequential throughput
  from ~41 to ~59 processes/s and saga from ~37 to ~59, but the saga's paced latency rose (p50 133 →
  456 ms): with two pods a process's events are handed between pods, and the relay's wake-up signal
  is per pod, so the hand-off waits for the other pod's 500 ms poll. Lowering
  `workflow.outbox-poll-interval-ms` is the documented lever.
- **Timer lateness is the scan interval.** With the default `workflow.timeout-scan-interval-ms` of
  10 s, a one-second timer fires 8–10 s late at the median: all 10,000 come due together and fire on
  the next scan. In the JPA modes the tail adds the time to work through 10,000 due timers at once.
  Lowering the interval is the lever — see the 1 s run below. A `TIMER` is accurate to its scan
  interval, not to the millisecond.
- **Synchronous invocation costs ~40 ms per request with a database** (one worker round trip plus the
  reply), and scales roughly with concurrency up to the throughput the mode allows.

## Caveats

- **One machine.** Pods, worker, driver, PostgreSQL and Redpanda share a laptop with other work. That
  is fine for comparing modes and builds with each other on the same machine, and it is not a
  scalability figure. The [Performance guide](/guides/performance/) covers what changes on a cluster
  (partitions × consumers become a first-order lever; on one machine they mostly contend).
- **`fsync=off`.** Testcontainers starts PostgreSQL without durable commits, which flatters every JPA
  figure against a production database. `-Dsuite.postgres.fsync=on` measures with them; see the
  comparison below.
- **Default tuning.** Every figure is at the shipped defaults (poll interval, scan interval, consumer
  concurrency, pool size). The existing harness in the same module shows what tuning them does.
- **Memory mode's saturation phases last under a second**, so they have no CPU samples and their
  throughput has the most run-to-run noise.
- **Messages and timers are bursts.** Their latency columns describe a 10,000-deep backlog draining,
  not the cost of one correlation; read their throughput.

## Reproduce it

```bash
scripts/benchmark.sh                       # all modes and scenarios, 3 repetitions (~3 h on this machine)
scripts/benchmark.sh -Dsuite.scale=0.01 -Dsuite.reps=1          # a smoke run, a few minutes
scripts/benchmark.sh -Dsuite.modes=memory,kafka1 -Dsuite.scenarios=sequential,sync
scripts/benchmark.sh -Dsuite.engine.workflow.timeout-scan-interval-ms=1000 -Dsuite.scenarios=timers
scripts/benchmark.sh -Dsuite.postgres.fsync=on
```

It needs Docker and JDK 21, builds the benchmark module and what it depends on from your checkout
(a reactor build — nothing is installed into `~/.m2`), starts PostgreSQL and Redpanda in
Testcontainers (removed when it ends), and writes `benchmark.json` (every repetition, every setting,
the environment) and `benchmark.md` to `modules/workflow-benchmark/target/benchmark/`. Knobs:
`suite.modes`, `suite.scenarios` (add `growth` for the history-growth check), `suite.reps`,
`suite.scale`, `suite.sync-seconds`, `suite.partitions`, `suite.pool-size`, and
`suite.engine.<property>` for any engine property. The script records the machine, the commit and
the load average before it starts. Publish the JSON with any number you quote from it.
