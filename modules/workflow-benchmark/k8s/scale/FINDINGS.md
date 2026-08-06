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
