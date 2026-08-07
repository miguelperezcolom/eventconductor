# Scale validation — findings

Results of running the scale-validation harness. Append each rung as it runs; the harness writes a
machine verdict, this is the human record beside it.

---

## Rung 1 — pipeline + crash recovery (2026-08-06, cloudfleet-hetzner)

**Purpose.** Shake out the cluster manifests and the controller, and prove the base pipeline and
crash recovery — NOT throughput (that is rung 2). Reduced footprint to keep it cheap: the NVMe node
selector stripped (this cluster had no local-NVMe node yet), Kafka topology spread relaxed to
`ScheduleAnyway`, 2 orchestrators, 1 worker, arrival rate 15/s.

**Topology deployed.** Postgres (single) · **Kafka 3-broker KRaft, RF=3** · 2 orchestrators
(published `orchestrator-standalone-app:1.0-beta.024`) · 1 worker + driver + verify
(`eventconductor-bench:scale`). Images are public — no pull secret needed.

### Rung 1a — base (no chaos)

| | |
|---|---|
| Processes | 2,600 (mixed: order-saga 1,051 · child 397 +397 spawned children · fanout 395 · timed 378 · linear 379) |
| Outcomes | 944 COMPLETED (ok) · 97 COMPENSATED (comp) · **10 COMPENSATION_FAILED** (compfail) · rest COMPLETED |
| Verdict | **PASS** — R1 conservation, R2 terminal (incl. children), R3a/R3b no-stuck + no silent-stall trap, R4 exactly-once, R5 outbox drained/no-poison, R7 saga outcomes match injected intent |

Validated the pieces flagged "needs cluster validation": the **3-broker KRaft Kafka RF=3**
StatefulSet forms its quorum and serves RF=3 topics; the orchestrator/worker/driver/verify manifests
and the `verify` Job (verdict + exit 0) all work; the full workload suite runs distributed;
`COMPENSATION_FAILED` is exercised at volume through the real Kafka+Postgres flow. Also confirmed the
`WorkflowInstaller` schema fix against the real orchestrator schema.

### Rung 1c — crash chaos

Same setup, `kubectl delete pod --force` on **2 orchestrator pods mid-load** (the canonical
commit-then-crash-before-dispatch case). chaos-mesh was NOT used here (its controller crashloops on
this cluster — see below); pod-kill needs no chaos-mesh.

| | |
|---|---|
| Processes | 2,135 |
| After 2 orchestrator kills | acked 2,135 == present 2,135 — **zero processes lost** |
| Verdict | **PASS** on all invariants |

Crash recovery works on the cluster: the transactional outbox, the in-flight step rearm on boot, and
the Kafka partition rebalance together lose nothing, and every process still reaches its correct
terminal status.

### Follow-ups before rung 2/3

1. **chaos-mesh controller-manager crashloops: `too many open files`** — the nodes' inotify limits
   are too low. Bump `fs.inotify.max_user_instances` / `max_user_watches` (a privileged sysctl
   DaemonSet, or the node image) before the richer network-fault scenarios (partition/latency/loss).
   Pod/broker/worker kills need no chaos-mesh (`kubectl delete pod`, as `k8s/reliability/chaos.sh`).
2. **Rung 2 needs a real local-NVMe node.** Rung 1 stripped the NVMe selector and ran at 15/s, which
   any storage sustains, so it did not measure the ceiling. Rung 2's whole job is to sweep the rate
   on NVMe until the completion slope flattens — that number sizes rung 3.

---

## Rung 2 — throughput ceiling (2026-08-06)

**Setup.** postgres + 3-broker Kafka + 4 orchestrators (1 vCPU each) + 2 workers on the `mateu-fleet`
CloudFleet NodePool, Postgres on `emptyDir` + `synchronous_commit=on`, right-sized from the start so
nothing restarts mid-run.

**A false start, recorded because it is a real trap.** An earlier attempt patched the running Kafka
StatefulSet's resource requests. Kafka here uses `emptyDir`, so the rolling restart **wiped each
broker's log**, breaking partition replicas → the orchestrators' producer failed with
`NOT_LEADER_OR_FOLLOWER` on the outbox topic, the outbox could not be relayed, and completions fell
to ~0 with commits stuck at ~137/s. That was a broken broker cluster, not a storage ceiling. **Lesson:
Kafka on `emptyDir` loses data on any pod restart — never patch the StatefulSet, and use PVCs for a
real run.**

**Clean result.** Measured the disk directly first: `pg_test_fsync` on `emptyDir` sustains
**~890-1,600 fdatasync ops/sec** single-threaded — a decent network SSD, ~3× the reliability rig's
~356 fsync/s block volume, but far from top local NVMe (10k-50k+/s). On a freshly-deployed, healthy
cluster the sustained completion ceiling is **~12-15 process instances/s** (arrivals above that just
grow the backlog; the engine holds ~300-375 commits/s ≈ **1/3 of the disk's single-thread fsync
budget** — group commit is not fully closing that gap under this write pattern). For reference the
same harness did ~120 steps/s on a laptop's real NVMe in compose.

**What it means for 20M.** ~13 PI/s → **20M ≈ ~18 days** on this cluster. Two levers to reach the
1-2 day target, in order of leverage:
1. **Faster storage.** A genuine local-NVMe instance (high concurrent-fsync) pinned to Postgres — the
   `eventconductor.io/storage=nvme` selector is for this. CloudFleet auto-selects Hetzner nodes whose
   `emptyDir` is this ~1k-fsync SSD; a dedicated-NVMe instance type (requested via an instance-type
   requirement on the NodePool/NodeClass) or a local-NVMe StorageClass would raise the fsync budget.
2. **Close the engine's 1/3-of-disk gap** (write batching / higher write concurrency so group commit
   batches more per fsync) and/or the **sharded topology** (design §4.3: N Postgres, processes hashed
   across them → ~N× the fsync budget) — the only path that scales writes past one node.

Until one of those lands, a 20M run on this cluster's storage is a block-storage-class multi-week
soak, which is exactly the outcome this rung exists to surface before booking the hardware.

**Rung 1 is unaffected:** it ran at 15/s, well under this ceiling, so its zero-loss + crash-recovery
verdicts stand.

---

## Rung 2 (cont.) — the storage lift is a dedicated-vCPU node, not exotic hardware (2026-08-06)

Lever 1 tested directly. Provisioned one Hetzner **ccx23** (dedicated vCPU, AMD EPYC-Milan) via
Karpenter and ran `pg_test_fsync` on its node-local `emptyDir` — the exact disk Postgres uses here:

| Node (emptyDir, 8kB, single thread) | fdatasync ops/s | fsync ops/s |
|---|---|---|
| shared-vCPU `cx23`/`cx43` (rung-2 above) | ~890–1,600 | — |
| **dedicated-vCPU `ccx23`** | **~8,250** | ~6,220 |

**~5–9× on the same storage tier.** The ceiling was never inherent Hetzner storage — it was
**noisy-neighbour CPU steal stalling the fsync syscall** on the shared-vCPU instances. A dedicated
vCPU removes it; `fdatasync` (Postgres's Linux default `wal_sync_method`) reaches ~8.2k ops/s. This
needs no local-NVMe StorageClass and no bare metal — just pinning Postgres to a CCX node, which the
existing CloudFleet pool provisions on demand via an instance-type nodeSelector (see 10-postgres.yaml;
CloudFleet blocks kubectl-created NodePools, so the `storage=nvme` label path needs the Fleet API).

**Revised 20M projection.** If the engine holds its ~1/3-of-disk write efficiency, ~8.2k fsync/s →
order-of ~2,700 commits/s → **~2–3 days for 20M** (was ~18). The exact figure needs the throughput
**sweep** on ccx23-backed Postgres — the next step — because at this rate the bottleneck likely moves
off the disk onto engine write-concurrency, Kafka, or CPU. Closing the engine's 1/3 gap (lever 2:
write batching / more concurrent committers so group commit batches more per fsync) then compounds on
top, and sharding (§4.3) remains the path past a single node.

**Constraint for the sweep.** The `mateu-fleet` NodePool `limits.cpu` is 24 (Fleet-API-managed, not
raisable via kubectl) and the live demo already uses part of it. A full RF=3 distributed sweep
(Postgres 3 + Kafka 3×2 + 6 orchestrators + 3 workers ≈ 19 vCPU of requests) does not fit alongside
the demo under that cap; it needs either the cap raised via the Fleet API or a trimmed topology
(1-broker RF=1 Kafka + ~3 orchestrators) that fits the free headroom.

### Throughput sweep on ccx23 — the disk stops being the bottleneck (2026-08-06)

Ran the trimmed topology that fits the cap: **Postgres on `ccx23`** (the 8.2k-fsync/s node) +
**1-broker Kafka RF=1** (`20-kafka-sweep.yaml`) + **4 orchestrators + 2 workers**, driver at a
deliberately-saturating **120/s** (≈10× the old ceiling), then drained with arrivals off.

| Phase | What the DB showed |
|---|---|
| Under 120/s load | Backlog exploded (live 2.8k → 36k), `outbox_unsent` climbed to ~24k, and COMPLETED/s **peaked ~15/s then collapsed** while ERROR/CANCELLED terminals rose. |
| Drain (arrivals = 0) | Flat-out **~100–115 terminal transitions/s** (COMPLETED ~52/s + backlog's timed-out ERRORs ~40/s); `outbox_unsent` cleared 24k → ~0 in ~100s (**~240/s relay drain**). |

**Reading it.**
- **The ccx23 disk is no longer the ceiling.** Flat-out the system does **~4–9× the shared-disk
  ~13/s** (COMPLETED ~52/s; terminal ~100–115/s). The storage lift translates into real throughput.
- **120/s was over-saturated on purpose, and it exposed a real hazard:** once the dispatch backlog
  exceeds `DEFAULT_STEP_TIMEOUT_MS` (30s here), steps start timing out *in the queue* → mass ERROR →
  saga cancellations → a **timeout death spiral** that depresses useful (COMPLETED) throughput. Final
  mix was 15,942 PENDING · 14,370 COMPLETED · 12,517 ERROR · 3,035 COMPENSATION_FAILED — an overload
  artifact, not a clean-workload result. Lesson: **never sustain arrivals above capacity**, and for
  high-throughput runs raise the step timeout well above worst-case dispatch latency.
- **The single-broker outbox relay is not the hard limit** — it drained ~240/s once it stopped
  competing with new commits. Under load the constraint is total write/CPU contention at this trimmed
  scale (4 orchestrators, 1 Kafka broker), not the disk and not the relay.

**Revised 20M projection.** Conservatively at the drain COMPLETED rate (~52/s) → **~4–5 days**; the
~100/s terminal rate suggests **~2–3 days** is reachable with (a) a **rate-controlled** sweep whose
arrivals sit just under capacity (no death spiral) + a higher step timeout, and (b) more topology
(more orchestrators / a 3-broker Kafka / more partitions) once the 24-vCPU cap is lifted or the demo
moved aside. That rate-controlled clean sweep is the next step; this run's job — show the disk lift is
real and find where the bottleneck moved (engine/CPU + step-timeout dynamics, not storage) — is done.

### Rate-controlled clean sweep — the bottleneck is pipeline concurrency, not any resource (2026-08-06)

Re-ran the same trimmed topology with `DEFAULT_STEP_TIMEOUT_MS=600000` (10 min, so an over-driven
backlog can't spiral into timeouts) at a steady 80/s. Clean this time: **72 ERROR out of ~55k rows**
(vs 12.5k in the saturated run), `outbox_unsent` held ~10 throughout.

**The honest sustained ceiling is ~28/s COMPLETED** (plateau 25.5 → 28.7 → 27.3 → 27.9 over the last
four intervals), ~2× the shared-disk ~13/s — **not** the 4–9× the flat-out drain burst suggested. The
drain number was inflated (it processed a pre-staged backlog with all resources freed); the steady
sustained figure is what a 20M run actually gets, and it is ~28/s → **20M ≈ ~8 days**.

**And nothing was resource-bound at that ceiling** (`kubectl top`, mid-run):

| Pod | CPU used | Note |
|---|---|---|
| postgres (ccx23) | ~0.87 vCPU | its 8.2k-fsync/s disk is nowhere near saturated |
| orchestrator ×4 | ~0.4–0.7 vCPU each | not CPU-maxed |
| kafka (1 broker) | ~0.4 vCPU | idle-ish |
| worker ×2 | ~0.1 vCPU each | idle |

So the ~28/s ceiling is **not CPU, not disk, not the relay** — it is **event-pipeline concurrency and
per-step latency**: each process advances one step at a time through outbox → Kafka → consumer →
commit, a process is pinned to a partition by business key, and the default 500 ms outbox poll adds
latency to cross-pod hand-offs. Throughput ≈ (concurrently-advancing processes) ÷ (per-step latency),
and both were left at defaults. **Adding raw resources (faster disk, more CPU) will not move this** —
the levers are *parallelism and latency*: lower `workflow.outbox-poll-interval-ms`, raise
`KAFKA_CONCURRENCY` and partition count, more orchestrators, and ultimately **sharding** (§4.3, N
independent pipelines). That, not storage, is what stands between ~8 days and the 1–2 day target — the
real, corrected outcome of rung 2.

### Rung 2b — pipeline tuning ~doubles the ceiling, confirming the diagnosis (2026-08-06)

Same trimmed topology and 80/s drive, but with the three pipeline levers turned up together (nothing
else changed):

| Lever | Untuned (rung 2 above) | Tuned |
|---|---|---|
| `workflow.outbox-poll-interval-ms` | 500 | **50** (via `-D` in JAVA_OPTS — the dashed property name does not survive env-var relaxed binding) |
| `KAFKA_CONCURRENCY` (per orchestrator) | 8 | **16** |
| Kafka partitions per topic | 48 | **96** (so 4 orch × 16 threads aren't starved) |

**Sustained ~56/s COMPLETED** (plateau; a driver-pod restart mid-run caused a transient dip + a ~90/s
catch-up burst, ignored) — **~2× the untuned ~28/s**, still clean (147 ERROR / ~67k rows). This
**confirms the bottleneck was pipeline concurrency + latency**: turning those knobs, and nothing else,
doubled throughput. Resources still had headroom at ~56/s (Postgres ~1.0 vCPU of 3, busiest
orchestrator ~0.9) — so the pipeline, not the hardware, was and remains the limit.

**Projection now ~4 days for 20M** (was ~8 untuned, ~18 on shared disk). The remaining gap to the 1–2
day target is more of the same lever plus horizontal pipeline width: higher concurrency, more
orchestrators (needs the 24-vCPU cap lifted or the demo moved), and **sharding** (§4.3) — N independent
outbox→Kafka→consumer pipelines, the only thing that scales this past one Kafka cluster's per-partition
ordering. Rung-2 ladder, in one line: **shared disk ~13/s → dedicated-vCPU disk ~28/s → pipeline-tuned
~56/s**, each step ~2×, none of them hardware-bound at the ceiling.

## Elastic sharding — first cluster validation (2026-08-07)

The 1M single-shard run above proved reliability at scale on one database; this proves the **sharded**
topology (`sharded/`) end to end. Two trimmed shards (Postgres + 1 orchestrator each, no ccx23),
1-broker Kafka, one worker per shard, driven at 8/s to ~4,082 processes, then drained and verified with
`Reconciler.verifyAcrossShards`:

```
PASS — shards=2   acked=4082   present=4082   R1..R7 all [ok]
```

Zero loss across shards (global R1: Σacked == Σpresent), even round-robin placement (shard0 ≈ shard1),
exactly-once over every step, and saga outcomes matching the injected intent — each shard running its
own independent outbox→Kafka→worker→reply pipeline.

**The engine's sharding code needed no change.** All five problems the run surfaced were deployment
configuration, now fixed in `sharded/`:

1. **Kafka DNS** — `20-kafka-sweep.yaml` hardcodes `.ec-scale.svc` in the advertised listeners and the
   KRaft quorum voters; a `namespace:`-only swap leaves the broker unroutable. Use a global `ec-scale`→
   target-namespace substitution.
2. **Schema race** — `shard.yaml` deploys a shard's orchestrator and its Postgres together; the engine
   applies its Flyway migrations at startup and, if it wins the race, that migration fails *non-fatally*
   and the pod runs schema-less yet reports healthy — so every creation dead-letters. Fixed with a
   `wait-for-postgres` initContainer.
3. **Definitions per shard** — a shard creates only from its own definitions; the `soak` driver used to
   install the suite on shard 0 only, so shard 1 dead-lettered every creation. `SoakDriver` now installs
   the suite (and the progress table) on every shard's database.
4. **Stale image** — the reused image tag plus the default `imagePullPolicy: IfNotPresent` served an old
   image from the node cache; the bench manifests set `Always`.
5. **Worker reply routing** — the engine's `WorkerReply.send("upstream", …)` is a StreamBridge dynamic
   send that does not honour a low-precedence `bindings.upstream.destination` property, so replies landed
   on the plain `upstream` topic no sharded orchestrator consumes and every step timed out. Fixed by
   supplying the destination as the high-precedence ENV
   `SPRING_CLOUD_STREAM_BINDINGS_UPSTREAM_DESTINATION=upstream-<shard>` on the worker — the same mechanism
   the orchestrator already uses. The transferable lesson for a real sharded worker deployment: pass the
   reply destination as an ENV, not a property.
