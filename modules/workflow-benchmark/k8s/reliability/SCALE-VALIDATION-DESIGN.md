# Production-scale reliability & performance validation — design

**Goal.** Prove, on the real cluster, that EventConductor runs **20 million processes** with
**realistic workloads and workers**, under **continuous chaos**, and **loses none of them and fails
none silently** — while collecting enough data to judge both **reliability** and **performance**.
The whole thing runs **unattended** and produces a machine-checkable **pass/fail verdict** plus an
artifact bundle to evaluate after the fact.

This is not a greenfield build. The reliability rig in this directory
(`10-soak.yaml`, `chaos.sh`, `invariants.sql`, `ec-reliability.sh`, `FINDINGS.md`) already did a
4-hour, 61,835-process chaos run and already computes the four zero-loss invariants. This design
**extends that rig** to 20M, adds realistic workloads + failure injection, a scalable reconciler,
continuous chaos, and an autonomous controller. It reuses everything it can and says exactly what is
new.

---

## 0. The one constraint that shapes everything: write throughput

`FINDINGS.md` measured the ceiling on this cluster: **~25 DB commits per process**, and Hetzner
block storage (`hcloud-volumes`) sustains **~356 fsync/s**, i.e. a single-instance Postgres tops out
around **~5–14 process instances/s**. At that rate **20M processes = ~17 days**. That is the
headline engineering fact, and the design is organised around it.

The bottleneck is a **single Postgres serialising commits on one WAL fsync stream** — adding
orchestrator pods or Kafka partitions does *not* raise it past the disk. There are exactly three
levers, in increasing order of effort:

1. **Faster WAL storage.** Put Postgres (or at least its WAL) on **local NVMe** instead of network
   block storage. Local NVMe does 10k–50k+ fsync/s → hundreds of PI/s on the same schema. This is
   the highest-leverage, lowest-code change and the first thing the pilot measures.
2. **Group-commit + concurrency tuning.** Under many concurrent writers Postgres batches commits per
   fsync. Raise writer concurrency (more orchestrator consumer threads / pods) and tune
   `commit_delay`/`commit_siblings` so effective commits/s ≫ raw fsync/s. **Do not** use
   `synchronous_commit=off` for reliability runs — it hides exactly the losses under test (the
   benchmark rig uses it; the reliability rig must not).
3. **Shard Postgres.** Partition processes by `hash(businessKey) % N` across **N independent
   Postgres instances**, each with its own orchestrator/worker pool and Kafka partitions → ~N× the
   fsync budget. Biggest lever, most work, and the only way to truly linear-scale writes. Treat as a
   stretch goal after 1–2 have been exhausted.

**Consequence for the plan:** the very first job of this effort is a **throughput-ceiling pilot** —
measure sustained durable PI/s on the target storage, then *size the 20M run from the measured
number* (duration, storage, partitions). We do not commit cluster-weeks blind. See §9 (ramp).

---

## 1. Success criteria (the autonomous pass/fail gate)

The controller (§7) emits PASS only if **all** hold, measured after load stops and the system is
given a bounded drain window:

| # | Criterion | Signal (source) |
|---|---|---|
| R1 | **Conservation** — every acked creation exists as a process | `acked (soak_progress) − present (process_entity) == 0` |
| R2 | **Terminal** — every started process reached a terminal status | `count(process_entity WHERE status NOT IN (COMPLETED,CANCELLED,ERROR,COMPENSATED,COMPENSATION_FAILED)) == 0` after drain |
| R3 | **No stuck steps** — no live step orphaned | `count(step_execution_entity WHERE status NOT IN terminal) == 0` after drain; **and** `WHERE deadline_at IS NULL AND status IN (PENDING,RUNNING) AND step_type IN (ACTION,RULE) == 0` (the 3,356-stuck trap — do not trust the gauge alone) |
| R4 | **Exactly-once** — no step executed twice | `step_execution_entity GROUP BY process_id, step_id HAVING count(*)>1 == 0` |
| R5 | **Outbox drained, no poison** | `outbox_message_entity WHERE status<>'Sent' == 0` **and** `WHERE status='Error' == 0` after drain |
| R6 | **No silent send loss** | outbox `Sent` count ≤ topic message count (topic ≥ outbox is fine — at-least-once; *fewer on the topic is the loss signature*) |
| R7 | **Expected saga outcomes** — failure-injected processes ended where the workflow says they should | terminal-status histogram matches the injected failure rate (e.g. N% → COMPENSATED, M% → COMPENSATION_FAILED) within tolerance |
| P1 | **Throughput sustained** — the run held its target arrival rate | `soak_progress.acked` slope ≈ target rate over the run |
| P2 | **Latency bounded** — engine cost per transition stayed under target | inter-step latency p99 (BenchmarkReport SQL) under the SLO at the run's utilisation |
| P3 | **Recovery bounded** — after each chaos fault, drain resumed within N minutes | completed-count slope recovers within the window (chaos.sh's recovery probe, made quantitative) |

R2/R3/R7 are **new** (the current `invariants.sql` checks conservation/exactly-once/drain/poison =
R1/R4/R5 but not per-process terminal status, the NULL-deadline trap, or saga-outcome shape).

---

## 2. Reuse map — what exists vs. what is new

| Component | Exists today | Change for 20M |
|---|---|---|
| Load driver (durable, acks=all, idempotent, `soak_progress`) | `SoakDriver` / `LoadDriver` | reuse; add per-definition weighting + failure-tag keys |
| Zero-loss reconciler | `invariants.sql` (R1,R4,R5) | **extend** to R2,R3,R6,R7 + make it scale to ~100M step rows (§6) |
| Chaos scenarios | `chaos.sh` (pod-kill, pod-kill-all, node-drain, db-stop, kafka-stop, rolling-upgrade, definition-change) | **wrap** in a continuous randomized loop + add network faults (§7) |
| Ops wrapper | `ec-reliability.sh` (deploy/status/verify/drain/psql) | reuse; the controller calls it |
| Cluster deploy | Helm `charts/eventconductor` (orchestrator/forms/rules + single PG + single Redpanda) | **new**: NVMe PG storage, multi-broker Kafka, a worker Deployment, partitions ≥ pods |
| Workload | single `bench-3-steps` (linear ACTION), no-op worker | **new**: realistic multi-definition suite + worker failure injection (§5) |
| Observability | Prometheus 24h/emptyDir, Grafana, Loki 7d, engine dashboard JSON, ServiceMonitor | **new**: long retention, provisioned dashboards, alert rules, artifact snapshots (§8) |
| Fault-injection reference | `workflow-dist-e2e/.../DistInfra.java` (pause/unpause, relay-block) | reference for realistic fault semantics |

---

## 3. Topology (k8s, namespace `ec-scale`)

```
                 ┌────────────── ec-scale ───────────────┐
 load-driver ──▶ │  Kafka (Redpanda, 3 brokers, RF=3,    │
 (Deployment,    │   partitions = P ≥ #orchestrators×cc)  │
  acks=all) ──▶  │                                        │
                 │  orchestrator × N  (Helm, HPA off)     │
 workers × M ──▶ │  worker × M  (new Deployment,          │
 (new Deployment)│    WorkerReply, failure injection)     │
                 │                                        │
                 │  Postgres (single, WAL on local NVMe;  │
                 │    OR N shards in the stretch topology)│
                 └────────────────────────────────────────┘
   observability ns: Prometheus (long retention, PVC) · Grafana (provisioned) · Loki · Tempo
   chaos-controller (Job): drives deploy→load→chaos→drain→verify→report, emits PASS/FAIL
```

Sizing rules (from the chart's own docs + FINDINGS):
- **Kafka partitions `P ≥ N × consumerConcurrency`** and set **before** topics auto-create; otherwise
  extra consumer threads get nothing. RF=3 across 3 brokers so a `kafka-stop` chaos loses a broker
  without losing the topic (the current RF=1 single broker makes broker-kill = total data-availability
  loss, which is not a fair reliability test).
- **`dbPoolSize × N ≤ postgres.maxConnections`.**
- **`defaultStepTimeoutMs` MUST be > the slowest task** (chart default is `0` = no deadline = the exact
  silent-stall trap; without it a lost dispatch is unobservable). This is the single most important
  config for a chaos run.
- Producer **`sync: true`** everywhere (already the default in the standalone worker/soak — never turn
  it off for reliability).
- **`workflow.persistence=jpa`** always (never `memory` — non-durable; the engine now warns on it).

---

## 4. The storage decision (pilot input)

Three candidate Postgres topologies, measured in the pilot, chosen by result:

1. **Block storage (baseline).** What FINDINGS used. ~5–14 PI/s. 20M ≈ weeks. Only acceptable if we
   deliberately want a long low-rate soak.
2. **Local NVMe.** Postgres `data`/WAL on a node's local NVMe (Hetzner dedicated-vCPU nodes have it).
   Expected 10–50× the fsync budget → hundreds of PI/s → 20M in ~1–2 days. **Recommended default.**
   Cost: local disk is ephemeral, so a node loss = DB loss — acceptable for a *test* cluster, and
   itself a chaos scenario worth running deliberately (node-drain with data on local disk tests the
   backup/restore story, or is explicitly out of scope).
3. **Sharded (stretch).** N Postgres, processes hashed across them, N orchestrator/worker
   pools. Linear write scaling; the only way to push past a single node's fsync rate. Build only if the
   1M pilot shows a single fast node can't hit the target rate. Fully designed in §4a.

## 4a. Sharding — how it would work

The single-Postgres ceiling is a WAL fsync stream: one node, one commit-serialisation point. Sharding
splits the *processes* across **N shared-nothing stacks**, so the total fsync budget is ~N×. Measured
here at ~13 PI/s/node, N shards give ~13N; on real NVMe (say ~40/node) far fewer shards reach the
target. It is the heavyweight lever — reach for it only when one fast node is not enough.

**The unit is the process.** Everything about a process — its `process_entity` row, all its
`step_execution_entity` rows, its outbox rows, its logs — lives on ONE shard, chosen by
`hash(businessKey) % N`. That is forced by the engine's model: a process advances in a per-process
transaction under single-writer (partition) ownership, so its state cannot straddle two databases.

**A shard is the stock engine, re-pointed by config — no code change.** The engine already
externalises both things a shard needs: the database (`spring.datasource.url` → `DB_URL`) and every
Kafka topic (Spring Cloud Stream binding `destination`s, defaulted by `KafkaBindingDefaults` to
`upstream`/`downstream`/`outbox`/`dead-letter`, overridable per binding). So **shard i** is the same
`orchestrator-standalone-app` image with `DB_URL=postgres-i` and its binding destinations suffixed
`-i` (`upstream-i`, …). N deployments, N Postgres, one Kafka cluster with per-shard topic names (or N
Kafka clusters). Each shard is a complete, independent mini-cluster: DB + orchestrators + workers +
topics.

**Routing happens once, at ingestion.** An external `ProcessCreationRequested` is produced to
`upstream-<hash(businessKey) % N>`. The load driver already holds the business key, so it shards at
the source (a real deployment puts a thin router in front of the message-API / Kafka ingress). After
that, everything about the process stays on its shard by construction.

**The three things that could cross a shard, and how each is handled:**

1. **Child processes (`PROCESS` step) — local for free.** A parent in shard i spawns a child by
   emitting `ProcessCreationRequested` through *its own* `upstream-i` binding, so the child is created
   in shard i. Parent↔child completion notification (`NotifyParentStepService`) reads the parent's
   `PROCESS` step in shard i's DB — same shard, works unchanged. Grandchildren likewise.

2. **Worker replies — local for free.** Shard i's workers consume `downstream-i` and reply via
   `WorkerReply` to `upstream-i` (their own bindings). A task and its reply never leave the shard, so
   the reply lands in the DB that owns the step.

3. **Messages (`SEND_MESSAGE` → `WAIT_FOR_MESSAGE`) — the one genuinely cross-shard case.** A send in
   shard i must be able to wake a wait in shard j, but neither knows the other's shard. Handle it with
   **one shared `messages` topic that every shard consumes**: `SEND_MESSAGE`'s `MessageReceived` is
   published there (not to a per-shard `upstream-i`), and each shard correlates it against *its own*
   local `WAIT_FOR_MESSAGE` subscriptions — a message that matches nothing locally is simply dropped,
   which is already the fail-closed contract. This is the only piece that is more than per-shard config:
   a binding that routes `MessageReceived` to the shared topic on publish and consumes the shared topic
   on every shard (a small, additive engine/config change). Volume is low (messages are rare relative
   to steps), so the shared topic is not a new bottleneck.

**The reconciler fans out.** `Reconciler.verify` runs its invariants against each shard's Postgres and
sums them: conservation is Σ(acked_i) vs Σ(present_i); exactly-once / no-stuck / outbox are per-shard
and unioned; the saga histogram sums across shards. Business keys already carry the intent, so the
verdict is shard-agnostic. The driver's `soak_progress` gets a shard column (or one row per shard).

**Cost.** N× the infrastructure, plus the ingress router, the shared `messages` topic, and the
shard-fanout reconciler. Operationally it is N clusters to watch. This is why it is the stretch option:
prove a single fast-NVMe node's ceiling first (rung 2 on real NVMe), and only shard if 20M ÷ that
ceiling is still an unacceptable wall-clock. (For reference, this is the same shard-by-workflow-id
principle Temporal/Cadence use for history shards; EventConductor's Kafka-partition-per-process model
just makes topic-per-shard the natural expression of it.)

---

## 5. Realistic workload suite (new)

Replace the single linear-ACTION `bench-3-steps` with a **weighted mix** of definitions that
exercise every path the engine promises — installed via the existing `WorkflowInstaller` (`install`
role) and selected per-process by the driver (weighted business-key prefixes):

| Definition | Exercises | ~weight |
|---|---|---|
| `order-saga` | ACTION→RULE→ACTION with `rollbackable` steps + `compensationStepId` — a payment/booking saga | 40% |
| `fanout` | FORK → k parallel ACTIONs → JOIN | 15% |
| `timed` | ACTION → TIMER (short) → ACTION (durable wait across restarts) | 15% |
| `message` | ACTION → WAIT_FOR_MESSAGE ⇢ SEND_MESSAGE from a sibling process (correlation) | 10% |
| `child` | PROCESS step spawning a child workflow, parent joins on it | 10% |
| `linear` | the current 3-ACTION path (throughput baseline / control) | 10% |

**Worker failure injection (new).** The benchmark worker is a `Thread.sleep` no-op. Replace with a
worker that, per task, draws from a configurable distribution:
- **latency**: think-time from a realistic distribution (not a constant), incl. a slow tail.
- **transient failure** rate `f_t`: throw / reply ERROR so the step **retries** (exercises the new
  `AWAITING_RETRY` backoff at scale) then succeeds.
- **permanent failure** rate `f_p`: always fail so retries exhaust → drives **saga compensation**;
  a sub-rate whose **compensation step also fails** → drives **`COMPENSATION_FAILED`** at volume
  (the code path shipped in beta.023, never yet exercised under load).
- All workers use **`WorkerReply`** (retry-then-throw) — an HTTP/naive reply reintroduces the
  historic reply-loss bug (§ silent-loss trap 5).

R7 then checks the terminal-status histogram matches `f_t/f_p` within tolerance — i.e. the engine
routed failures to the outcomes the DSL says it should, at 20M scale, through chaos.

---

## 6. The scalable reconciler (new)

`invariants.sql` is correct but does full `GROUP BY` scans over `step_execution_entity`. At 20M
processes × ~5 steps = **~100M rows**, and while chaos is killing things, that must not itself fall
over. Design:

- **Out-of-band accounting stays the source of truth for "handed over"**: the driver's
  `soak_progress` (acks=all, idempotent, written to the DB once/sec so a killed driver's count
  survives) — reused as-is; extended with per-definition counts for R7.
- **Incremental/partitioned reconciliation** instead of one giant scan: reconcile in
  **business-key ranges** (the driver assigns `<def>-<shard>-<i>`), each range a bounded query using
  the existing indexes (`idx_step_exec_process_status`, `idx_step_exec_status`,
  `idx_step_exec_deadline`). Run ranges in parallel; aggregate. This also localises *which* processes
  failed a check instead of an all-or-nothing count.
- **Exactly-once at scale**: the `GROUP BY process_id, step_id HAVING count>1` is the expensive one;
  run it per key-range and union the (tiny, expected-empty) violation set.
- **The NULL-deadline trap (R3b)** and **saga-outcome histogram (R7)** are new queries, both index-
  backed.
- **R6 (send loss)**: compare outbox `Sent` count to Kafka topic end-offsets (rpk / admin API) per
  topic; topic ≥ outbox passes.
- Output: a **structured verdict** (JSON + human summary) — per-invariant pass/fail, counts, and the
  *exact list* of any offending process/step ids. This is the artifact I evaluate.

Consider partitioning `process_entity`/`step_execution_entity` by a hash of `business_key` (or by
time) at 20M so the reconciler scans and Postgres autovacuum stay sane; and an **archival/prune**
step for completed processes mid-run if we choose to keep tables bounded (a real operational finding
either way — 20M processes is tens of GB).

---

## 7. Continuous chaos + the autonomous controller (new)

**Chaos loop.** `chaos.sh` already has the scenario library (pod-kill, pod-kill-all, node-drain,
db-stop, kafka-stop, rolling-upgrade, definition-change) and injects faults the honest way (SIGSTOP
pause for DB/broker so they return at the same address/state; abrupt pod close for crash). New: a
**continuous randomized driver** that, on a schedule, picks a weighted-random scenario, injects it,
waits a randomized dwell, heals, and records a **fault-event log** (what, when, duration) so the
reconciler and latency series can be correlated to faults. Add **network faults** (latency / loss /
partition — via `tc`/NetworkPolicy or a chaos-mesh install) which the current stop/kill-only set
lacks. Keep RF=3 Kafka so broker chaos is survivable.

**Controller** (a k8s Job = the single unattended entrypoint) runs the pipeline and is the only thing
a human starts:

```
deploy(topology) → install(definitions) → start(workers,driver@rate)
   → run continuous chaos for T  → stop driver → wait drain(bounded)
   → reconcile(R1..R7) → snapshot(metrics,logs,verdict) → emit PASS/FAIL + exit code
```

It never needs babysitting, but its state is observable at any time (`ec-reliability.sh status`,
Grafana, and a heartbeat line to stdout/Loki). Non-zero exit on any failed criterion.

---

## 8. Observability & the data I evaluate

Wire up what mostly exists, fix the run-length gaps:
- **Dashboards provisioned** (not hand-imported): the existing `eventconductor-engine.json` already
  charts the right things — stalled steps, outbox pending, dead-lettered, retries, compensations,
  consumer lag, process outcomes. Add the **fault-event annotations** so faults line up with the
  series.
- **Long retention**: Prometheus 24h/emptyDir loses a multi-day run and wipes on restart → give it a
  PVC + retention ≥ run length (or remote-write to a durable store). Loki 7d is fine.
- **Alerts**: turn on Alertmanager with rules on `eventconductor_steps_stalled` (**max across
  replicas**, never sum), `eventconductor_outbox_pending` sustained > 0, `events_dead_lettered` rate,
  `compensations_failed` rate — the silent-failure signals.
- **Artifact bundle** the controller collects for me: the reconciler JSON verdict, the
  `soak_progress` final counts, per-invariant offending-id lists, the fault-event log, Prometheus
  snapshots of the key series (throughput, p99 latency, stalled, outbox backlog, consumer lag) as
  CSV/PNG, and a `FINDINGS.md`-style written summary. Both **reliability and performance** are in the
  bundle.

---

## 9. Ramp plan — never commit cluster-weeks blind

Each rung is a gate; we only advance on a clean PASS:

| Rung | Scale | Purpose | Runs on |
|---|---|---|---|
| 0 | `docker-compose.dist.yml` | smoke the new workloads + worker failure injection + reconciler locally | laptop |
| 1 | **100k** | validate the full pipeline end-to-end incl. chaos + verdict; find the storage ceiling per topology | cluster, hours |
| 2 | **1M** | confirm the reconciler + Postgres + retention hold at 10×; **measure sustained PI/s** → size rung 3 | cluster, hours |
| 3 | **20M** | the target: sustained realistic load + continuous chaos, autonomous, verdict + bundle | cluster, sized from rung 2 (hours–days) |

Rung 2's measured rate decides rung 3's storage/shape and duration. If a single NVMe node can't hit a
rate that makes 20M finish in an acceptable window, rung 3 uses the **sharded** topology (§4.3).

---

## 10. Decisions taken (2026-08-06)

- **Storage/timeline**: local NVMe single node → target ~1–2 day 20M run (§4.2). Sharding (§4.3)
  only if the 1M pilot shows a single NVMe node can't hit the needed rate.
- **Chaos tooling**: install **chaos-mesh** (§7) for richer faults (network partition/latency/loss,
  clock skew) on top of the existing `chaos.sh` scenario semantics.
- **Build**: start now with the pure-code phase (§11.1) — realistic workloads + failure-injecting
  worker + scalable reconciler — validated on `docker-compose.dist.yml` before any cluster work.

## 10b. Open decisions (for Miguel)

1. **Storage**: local NVMe single node (recommended) vs. block storage long-soak vs. build sharding
   now. Decides rung-3 duration.
2. **20M timeline tolerance**: is a ~1–2 day run acceptable (NVMe), or must it be hours (sharding)?
3. **Chaos tooling**: extend `chaos.sh` (bash/kubectl, zero new deps) vs. install **chaos-mesh**
   (richer faults: network partition/latency/loss, clock skew — worth it for realism).
4. **Kafka**: stand up a 3-broker RF=3 Redpanda (needed for fair broker-chaos) — new manifest.
5. **Cost/box**: rung-3 on `cloudfleet-hetzner` for 1–2 days of dedicated nodes — confirm budget.

## 11. Build order — status

1. ✅ **DONE, validated on compose.** Realistic workload suite (§5:
   `scale/{order-saga,fanout,timed,child,child-work}.json`) + failure-injecting worker
   (`BenchmarkWorkerApp`) + weighted driver (`Workload`/`ScaleWorkload`) + the extended reconciler
   (§6: `Reconciler` + `verify` role, R1–R7). A 349-process mixed run returns PASS on all
   invariants, including COMPENSATION_FAILED exercised at volume through the real Kafka+Postgres
   flow. (Also fixed a pre-existing `WorkflowInstaller` schema bug.)
2. ✅ **AUTHORED, YAML-validated, needs cluster validation.** `k8s/scale/`: NVMe Postgres,
   3-broker RF=3 Kafka, orchestrator (N replicas, timeout>0), failure workers, soak driver, verify
   Job.
3. ✅ **AUTHORED.** `k8s/scale/chaos/scale-chaos.yaml` (chaos-mesh Schedules) + `run-scale.sh`
   (autonomous controller: deploy→load→chaos→drain→verify→collect→PASS/FAIL) + README runbook.
4. ⏳ **NEXT — needs the cluster.** Build/push the benchmark image; label an NVMe node; install
   chaos-mesh; run **rung 1 (100k)** to shake out the manifests, **rung 2 (1M)** to measure the
   NVMe ceiling and size **rung 3 (20M)**.
</content>
