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

## Rung 2 — throughput ceiling (2026-08-06, partial — storage-bound)

**Setup.** Full-ish footprint (postgres + 3-broker Kafka + 4 orchestrators + 2 workers) on the
`mateu-fleet` CloudFleet NodePool, Postgres on `emptyDir` with `synchronous_commit=on`. Drove at
200/s.

**Result — a storage ceiling, not a compute one.** At 200/s the driver kept acking (broker fine)
but the engine backed up immediately: ~24k steps stuck in `CREATED`, ~3.4k outbox `Pending`, ~1
process completed in 100s. Bumping orchestrator CPU did **not** move it — the completion rate stayed
~15-20 steps/s and Postgres held a **flat ~137 commits/s** (`xact_commit` delta). No errors: pure
write-throughput saturation.

**Conclusion: `emptyDir` on the default CloudFleet nodes is NOT fast local NVMe.** ~137 commits/s
with `synchronous_commit=on` is block-storage class (slower even than the ~356 fsync/s the reliability
FINDINGS measured), a fraction of the thousands/s local NVMe gives. On my laptop's real NVMe the same
harness did ~120 steps/s in compose; here it is ~15-20. So the cluster's default node storage is
network-backed, and the design's central lever — genuine local NVMe — is **not** delivered by
`emptyDir` on this NodePool.

**Next (a provisioning decision for the operator).** To get the hundreds-of-PI/s that put 20M in
~1-2 days, provision a node with **genuine local NVMe** (a Hetzner instance type with a local NVMe
disk, exposed to the pod via `hostPath`/local-path or the instance's ephemeral NVMe mount) and pin
Postgres there — the `eventconductor.io/storage=nvme` selector is exactly for this. If a single NVMe
node still can't hit the target rate, go to the sharded topology (design §4.3). Only then is the
rung-2 ceiling sweep (and rung 3's 20M) meaningful — on this cluster's default storage 20M would take
the block-storage-class weeks, which is the number this rung exists to avoid.

**Rung 1 is unaffected:** it ran at 15/s, well under this ceiling, so its zero-loss + crash-recovery
verdicts stand.
