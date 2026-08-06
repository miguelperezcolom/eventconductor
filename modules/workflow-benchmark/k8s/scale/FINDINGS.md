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

## Rung 2 — throughput ceiling (pending)

_To run on a local-NVMe node: label it `eventconductor.io/storage=nvme`, then
`./run-scale.sh --rate <sweep> --minutes 60` at increasing rates until `soak_progress.acked` stops
tracking the arrival rate. The plateau is the sustained ceiling; 20M ÷ ceiling sizes rung 3._
