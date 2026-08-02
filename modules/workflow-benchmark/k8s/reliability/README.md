# Reliability run

A repeatable answer to a question the benchmark does not ask: **does the engine lose anything when
the cluster underneath it breaks?**

The benchmark next door measures how fast a transition is. This measures whether a process that was
accepted still finishes after a pod is killed, a node is taken away, the database is stopped, the
broker is stopped, the engine is redeployed, and the workflow definition is edited under load.

Speed shows up here only as a constraint: the load has to stay under what the cluster can sustain,
or every scenario measures a backlog instead of a recovery.

## What the verdict is computed from

Nothing in the harness's memory. The count of work genuinely handed over lives in the database, in
a table the driver writes once a second:

```
soak_progress(prefix, attempted, acked, failed, started_at, updated_at)
```

`acked` is the number of creation events the **broker acknowledged**, with `acks=all` and an
idempotent producer so a send retried across a broker restart cannot become two processes. That
number is the contract, and the driver, a pod, or a whole node can die at any moment without
invalidating it. Everything else — conservation, exactly-once, drain — is computed against it by
`invariants.sql`, reading the engine's own tables.

The invariants:

| | Must hold |
|---|---|
| **Conservation** | processes in the engine = creations acknowledged by the broker |
| **Exactly-once** | no `(process, step)` pair has more than one execution row |
| **Drain** | after the load stops: no live process, no live step, no unsent outbox row |
| **No poison** | no outbox row parked in `Error` |

Retried steps are reported but are not failures — a retry is the engine recovering.

## Running it

The engine must already be deployed (see `charts/eventconductor`). Then:

```bash
export BENCH_IMAGE=<user>/eventconductor-bench:<tag>
export SOAK_RATE=4                 # processes per second; see "Choosing the rate"
./ec-reliability.sh deploy         # load driver + workers, into the engine's namespace
./ec-reliability.sh watch 10       # one line every 10s
```

Break things, in any order, as many times as you like:

```bash
./chaos.sh pod-kill
./chaos.sh node-drain
./chaos.sh db-stop 90
./chaos.sh kafka-stop 90
./chaos.sh rolling-upgrade
./chaos.sh definition-change
./chaos.sh all                     # every scenario, with a settle window between them
```

Each scenario prints a **recovery time** — measured as the moment the finished count starts rising
again, not as the moment pods report Ready. Those are different claims, and only the first one
means the engine is working: a pod can be Ready and consuming nothing, which is a failure mode this
project has already shipped once.

Then get the verdict:

```bash
./ec-reliability.sh drain 900      # stop the load, wait for the engine to finish, print invariants
```

`reset` wipes every soak process and counter so the next run starts clean. Note it does **not**
clear Kafka: leftover creation events from a previous run will be consumed after a reset and show
up as processes with no matching `acked`, which reads as a conservation failure that never happened.
To start genuinely clean, scale the engine to zero, delete and recreate the topics, then reset.

## Choosing the rate

Load above what the cluster sustains turns every scenario into a measurement of backlog depth. Find
the ceiling before running anything:

```bash
# with the load stopped and a backlog present, how fast does it drain?
./ec-reliability.sh psql "SELECT count(*) FROM process_entity WHERE business_key LIKE 'soak-%' AND status='COMPLETED'"
```

Then set `SOAK_RATE` to roughly two thirds of that, and confirm `live=` in `status` is flat rather
than climbing.

On this engine the ceiling is almost always the database's **fsync rate**, not CPU. Every domain
event costs about 2.5 commits and every commit costs one fsync, so:

```bash
kubectl -n ec-rel exec deploy/ec-eventconductor-postgres -- \
  sh -c 'pg_test_fsync -f /var/lib/postgresql/data/fsynctest -s 5'
```

divided by ~25 is the order of magnitude of processes per second the cluster can do, whatever the
CPU graphs say. A network block volume at ~350 fsync/s and a local NVMe at tens of thousands are
two different machines for this workload, and a figure from one says nothing about the other.

## What is deliberately not tuned

`synchronous_commit` stays on. Turning it off would multiply throughput and quietly invalidate
every claim on this page, because the losses it permits are exactly the ones being tested for.

## Files

| | |
|---|---|
| `10-soak.yaml` | the load driver and the workers, in the engine's namespace so they fail with it |
| `20-install-job.yaml` | replaces the workflow definition mid-run, for the definition-change scenario |
| `invariants.sql` | the verdict |
| `ec-reliability.sh` | deploy, watch, drain, verify, reset |
| `chaos.sh` | the scenarios |
