# Scale validation — cluster run

Runs the realistic reliability suite at scale, under continuous chaos, autonomously, and emits a
PASS/FAIL verdict plus an artifact bundle. Design and rationale:
[SCALE-VALIDATION-DESIGN.md](../reliability/SCALE-VALIDATION-DESIGN.md). The workload, the
failure-injecting worker and the reconciler are validated on `docker-compose.dist.yml` first (see
the module README); this directory is the cluster deployment of the same pieces.

## What gets deployed (namespace `ec-scale`)

| File | What |
|---|---|
| `00-namespace.yaml` | the namespace |
| `10-postgres.yaml` | **Postgres on local NVMe**, `synchronous_commit=on`, group-commit tuned — the throughput lever |
| `20-kafka.yaml` | **3-broker Kafka, RF=3** (a broker-kill is survivable, not total loss) |
| `30-orchestrator.yaml` | the engine under test (published image), N replicas, `DEFAULT_STEP_TIMEOUT_MS>0` |
| `40-worker.yaml` | failure-injecting workers (benchmark image) |
| `50-driver.yaml` | the load driver (soak, scale workload) |
| `60-verify-job.yaml` | the reconciler (verdict + exit code) |
| `chaos/scale-chaos.yaml` | chaos-mesh Schedules (pod/broker kills, network partition/latency) |
| `run-scale.sh` | the autonomous controller — deploy → load → chaos → drain → verify → collect |

## Prerequisites

1. **Images**, published and CVE-clean (≥ `1.0-beta.024`):
   `miguelperezcolom/orchestrator-standalone-app:<tag>` and the benchmark image. Build/push the
   benchmark image with `modules/workflow-benchmark/Dockerfile` (see the module README).
2. **NVMe node label** — the storage lever. Label the NVMe node pool so Postgres lands there:
   `kubectl label node <nvme-node> eventconductor.io/storage=nvme`.
3. **Pull secret** in the namespace: `kubectl -n ec-scale create secret docker-registry regcred …`.
4. **chaos-mesh** installed (only for `--chaos`): `helm install chaos-mesh chaos-mesh/chaos-mesh -n chaos-mesh --create-namespace`.
5. **Observability** (optional but recommended): the `demo/.devops/observability` LGTM stack, with
   the engine dashboard `eventconductor-engine.json` and Prometheus retention ≥ the run length
   (the shipped 24h/emptyDir loses a multi-day run — give it a PVC + longer retention).

## Run it — the ramp (never commit cluster-weeks blind)

Each rung is a gate; advance only on a clean PASS.

```bash
export ENGINE_IMAGE=miguelperezcolom/orchestrator-standalone-app:1.0-beta.024
export BENCH_IMAGE=miguelperezcolom/workflow-benchmark:1.0-beta.024

# Rung 1 — 100k-ish: validate the whole pipeline incl. chaos + verdict. Short.
./run-scale.sh --rate 100 --minutes 20 --chaos

# Rung 2 — 1M-ish: MEASURE the sustained ceiling on NVMe → this sizes rung 3.
#   Sweep --rate upward until acked slope flattens (attempted-vs-acked gap grows); that rate is
#   the ceiling. 1M at the measured rate tells you rung 3's duration.
./run-scale.sh --rate 300 --minutes 60 --chaos

# Rung 3 — 20M: sized from rung 2. At ~200 PI/s that is ~28h; at ~600 PI/s ~9h.
#   process count ≈ rate × minutes × 60. For 20M at 300/s: ~1110 min (~18.5h).
./run-scale.sh --rate 300 --minutes 1110 --chaos
```

The controller runs unattended. Watch it live if you like (`kubectl -n ec-scale get pods`, Grafana),
but it needs no input. It exits 0 on PASS, 1 on FAIL.

## The verdict and the bundle

`run-scale.sh` writes to `artifacts/<prefix>/`:

- `verdict.txt` — per-invariant PASS/FAIL + JSON (R1 conservation, R2 terminal incl. children,
  R3 no stuck / no silent-stall trap, R4 exactly-once, R5 outbox drained / no poison, R7 saga
  outcomes match injected intent).
- `soak_progress.txt` — the acked count (the conservation contract).
- `outcomes.txt` — the definition × terminal-status histogram.
- `metrics.json` — a snapshot of the alert-worthy engine series.
- `orchestrator.log`, `pods.txt` — for post-mortem.

A PASS means: of every process the driver acked, all reached a terminal status, none stuck (not even
the silent deadline-less kind), no step ran twice, the outbox fully relayed with no poison, and every
injected failure landed in the terminal status the DSL says it should — through hours of pods,
brokers, and networks being broken underneath it.

## Sizing notes (from FINDINGS.md + the design)

- **Storage is the ceiling.** Block storage ≈ 14 PI/s (20M ≈ 17 days); local NVMe is the point of
  this rig. If rung 2 on one NVMe node still can't hit the rate you need, go to the sharded topology
  (§4.3 of the design): N Postgres, processes hashed across them, N orchestrator/worker pools.
- **Partitions ≥ orchestrator replicas × KAFKA_CONCURRENCY** (48 here for 6×8); raise Kafka
  `KAFKA_NUM_PARTITIONS` before topics auto-create when you scale pods.
- **`DEFAULT_STEP_TIMEOUT_MS>0` is mandatory** — without it a lost dispatch is invisible.
