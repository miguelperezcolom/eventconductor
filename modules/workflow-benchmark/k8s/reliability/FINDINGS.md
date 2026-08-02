# Reliability run — 2 August 2026

Four hours of continuous load against a three-node Kubernetes cluster (Hetzner, managed by
Cloudfleet), with seven failure scenarios injected while it ran.

| | |
|---|---|
| Engine | 3 orchestrator pods, `kafka` + `jpa` |
| Infrastructure | PostgreSQL 16, Redpanda v24.1.7, 6 partitions per topic |
| Load | 4 processes/s, 3 sequential worker steps each (4 after the definition change) |
| Total | 61 835 processes, 642 912 outbox messages, 230 417 task dispatches |
| Harness | `ec-reliability.sh`, `chaos.sh`, `invariants.sql` in this directory |

## Verdict

**Not yet production-ready for enterprise**, and the reason is specific: a single lost message
leaves a process stopped forever with nothing anywhere reporting it. Everything else held up
well, including several things that are hard to get right.

### What held

| Invariant | Result |
|---|---|
| Exactly-once | **0** steps executed twice, across 230 417 dispatches that included 44 genuine Kafka redeliveries |
| Poison handling | **0** outbox rows parked as `Error` |
| Outbox drain | **0** rows left unsent |
| Definition change under load | 13 564 processes finished on the old definition, 44 910 on the new. **No hybrids** |

The exactly-once number is the strong one. At-least-once delivery redelivered 44 task requests,
and `StartStepExecutionUseCase`'s idempotency absorbed every one. The definition-change result
confirms the claim the design rests on: a process runs against the copy it froze at creation, and
editing the definition underneath thousands of running processes corrupted none of them.

### Recovery from each scenario

Recovery is measured as the finished count rising across two consecutive samples — not as pods
reporting Ready, which is a different and weaker claim.

| Scenario | Progress resumed |
|---|---|
| One orchestrator pod killed | 12 s |
| Whole orchestrator tier killed | 54 s (count frozen for 42 s, then rising) |
| Rolling redeploy under load | 18 s |
| Broker down 90 s | 13 s after it returned |
| PostgreSQL down 90 s | 12 s after it returned |
| Node drained | 24 s |
| Definition replaced mid-flight | 13 s |

An earlier version of the harness reported "6 s" for all seven. That was one poll interval, and
what it measured was transactions that were already committing when the pods were killed. The
number above only counts sustained progress.

### What failed

**3 356 processes stopped permanently.** 3 308 steps were left in `PENDING`, every one dispatched
inside the 90-second broker outage. The arithmetic identifies the loss precisely: the broker holds
230 417 task requests on `downstream` and 227 065 replies on `upstream`. The workers consumed
every request and 3 352 replies were never published.

That was the harness's worker, not the engine — `streamBridge.send(...)` returns `false` when the
broker will not take the message and the code ignored it, so the listener returned normally, the
offset was committed, and the completed task was never reported. But it was also the exact pattern
every worker in this repository used, including the sample worker other workers get copied from.

And the engine had no backstop. All 3 308 stalled steps had `deadline_at` null, because
`timeoutSeconds` is opt-in per step and the timeout scan is an index range over the deadline. A
step without one is not merely un-timed-out, it is unobservable: nothing in the engine would ever
look at it again, and nothing reported a problem.

**71 outbox messages marked `Sent` that the broker never received** — 642 912 rows against 642 841
in the topic. `OutboxDrain` delivers before marking, which is the right order, but the delivery was
an asynchronous `streamBridge.send` whose return value was discarded. Asynchronously there is no
such thing as a failed delivery at the moment of sending: the record is buffered, `true` is
returned, and the row is marked. This one is the engine's own, in the component whose entire job
is not to do that.

**Conservation −5.** Five `process-creation-requested` events were dead-lettered and their
processes do not exist. Parked rather than silently dropped — they can be replayed from the
dead-letter topic — but the broker acknowledged them and the engine did not create them.

## Fixes made in response

| | |
|---|---|
| `SynchronousProducerDefaults` | The engine now defaults Kafka producers to synchronous, so a refused send is knowable. Contributed by the engine rather than each application's YAML, because it is a correctness property of the outbox, not a deployment preference. |
| `PartitionedEvents.send` | Throws when the broker refuses. In the relay that leaves the row `Pending`; in the consumer-side publishers it fails the handler so the offset is not committed. |
| `WorkerReply` | The reply path, written once: retries a refused send and finally throws, so the task is redelivered rather than lost. The sample worker and the harness worker both use it. |
| `eventconductor.steps.stalled` | A gauge, plus a periodic WARN, counting live steps with no deadline that have waited too long. Reporting only — but the silence was the worst part of this failure. |
| `workflow.default-step-timeout-ms` | A fallback deadline for ACTION and RULE steps that declare none, so a lost message is recovered by the existing retry path. Off by default, and never applied to USER_TASK, PROCESS or WAIT_FOR_MESSAGE, whose waiting is unbounded by design. |

## Performance, and why the number here is small

The engine sustained roughly **5 processes/s**, with a ceiling near 10. The laptop benchmark
reports 78–91. Both are true, and the difference is not the engine.

Every domain event costs about 2.5 database commits — 25 per process instance — and every commit
costs one `fsync`. The cluster's block storage does 356 `fsync`/s:

```
open_datasync    342.289 ops/sec    2922 usecs/op
fdatasync        356.145 ops/sec    2808 usecs/op
```

356 ÷ 25 ≈ 14 processes/s as an absolute ceiling, and PostgreSQL was measured committing at 258/s
while the orchestrators sat at 200–600 millicores of a 1000m limit. The engine was never the
constraint; the disk was. A local NVMe does tens of thousands of `fsync`/s, which is the whole
gap.

`synchronous_commit` was deliberately left on. Turning it off would multiply the throughput and
invalidate every claim on this page, because the losses it permits are exactly the ones being
tested for.

The number worth attacking is not the throughput but the **25 commits per process instance**.

## Deployment defects found on the way

Five, all of which required an actual cluster to see. Fixed in the same branch:

1. **Flyway had never run in any deployment.** Spring Boot 4 moved `FlywayAutoConfiguration` into
   a module nothing declared, so `spring.flyway.enabled=true` was inert — and with the chart's
   `ddlAuto=validate` the application could not start at all.
2. **Two `V11` migrations.** Both merged green because nothing on the build path reads these
   filenames. A test does now.
3. **One consumer thread per binding.** A deployment could never use more partitions than it had
   replicas; three pods left two of six partitions unread with no pod near CPU-bound.
4. **No resource requests on PostgreSQL or Redpanda.** BestEffort QoS, so they are the first pods
   evicted — scaling the orchestrator from 3 to 6 replicas evicted the broker and stopped the
   engine entirely.
5. **Redpanda's shard count unpinned.** It is written into the data directory as an invariant, so
   a broker rescheduled onto a smaller node refuses to start, permanently. On a cluster with a
   node autoprovisioner that turns a routine reschedule into an outage.

## Reproducing this

```bash
export BENCH_IMAGE=<user>/eventconductor-bench:<tag>
export SOAK_RATE=4
./ec-reliability.sh deploy
./chaos.sh all
./ec-reliability.sh drain 3000
```

See `README.md` for how to pick the rate, and why running above the sustainable one makes every
scenario measure backlog instead of recovery.
