# Analytics read model

The analytics report (`/workflow/analytics`, and the `getWorkflowAnalytics` / `findBottleneck` MCP
tools) is a set of `GROUP BY`s over `process_entity` and `step_execution_entity`. Answered directly,
each one has to touch every row in the window — it is **O(rows)**, and an index cannot help an
aggregate that reads them all. Measured on a reference deployment (PostgreSQL 16, 388k processes,
2.7M step executions, 3.8 GB): the step aggregate alone took ~1.2 s in the database on every page
load, and grows linearly with history.

This is the opt-in read model that answers the same report from **daily rollups** instead — a few
dozen rows per window, read in milliseconds, independent of how much history has accumulated.

## Turning it on

```yaml
workflow:
  persistence: jpa            # the read model is a jpa-mode feature
  analytics:
    rollup: true              # off by default; nothing changes when unset
```

When `workflow.analytics.rollup=true`, `ProcessDBRepository` / `StepExecutionDBRepository` delegate
`aggregateProcesses` / `aggregateSteps` to `RollupAnalyticsReader`. When it is unset they run the
existing SQL, and the memory-mode stores are untouched either way.

Tunables (defaults shown):

```yaml
workflow:
  analytics:
    rollup-interval-ms: 15000            # how often the projector folds
    rollup-batch-size: 1000              # rows folded per batch
    rollup-ceiling-seconds: 2            # trailing edge, to stay off rows still committing
    rollup-max-batches-per-cycle: 200    # bounds lock/connection hold during first-run backfill
```

## How it stays current

`AnalyticsRollupProjector` runs on a daemon thread under one advisory lock (id `333444555`), the same
shape as the engine's timeout scheduler. It folds three streams of facts, each behind a
`(timestamp, id)` cursor it advances in the same transaction that applies the batch:

- a process the moment it is **created** → `process_created_daily`
- a process the moment it **finishes** → `process_finished_daily`, `process_status_daily`,
  `process_duration_daily`
- a step the moment it **finishes** → `step_status_daily`, `step_duration_daily`

Because the cursor only moves forward, **history is never re-scanned** — steady-state work is a
batch or two. The first run finds the cursors at the epoch and drains all of history in bounded
batches: that is the backfill, no separate path.

### A finish can be taken back

A creation is immutable; a finish is not. `RetryProcessUseCase` and `RestartProcessUseCase` reopen a
finished process (`finished = NULL`, back to `RUNNING`) and reset its step executions to `CREATED`
(`finished_at = NULL`); the engine's own step retry clears a step's `finished_at` too. So every
contribution the projector adds to the rollups is also written to a **ledger**, in the same
transaction:

- `analytics_counted_process` — per process id: definition, creation day, finish day, status, duration
- `analytics_counted_step` — per step execution id: definition, step, creation day, status, order,
  duration

— exactly the fields a rollup key and its additive values are made of. With it:

- **Reopened** — each pass first looks for counted rows whose source row is in flight again
  (`finished IS NULL` / `finished_at IS NULL`, joined to the ledger by primary key: the same set the
  live overlay counts, not a scan of history), subtracts their contribution from the rollups
  (`DurationHistogram.mergeOut` for the histograms) and deletes the ledger entries. The live overlay
  then counts the row, once.
- **Finished again** — a finish whose id is already in the ledger (reopened and finished again between
  two passes, or a replayed batch) has its old contribution subtracted before the new one is added, and
  the entry is updated. So re-folding the same fact is a no-op, not something only the cursor prevents.
- A rollup row a retraction brings to zero is deleted, so after any sequence of reopens and finishes
  the rollups equal a fresh rebuild from the raw tables — which is what the tests assert.

Two pods stay safe for the same reasons as before: one advisory lock serialises the folds, and each
retraction or swap commits with its ledger change, so a crash re-runs a batch rather than half-applying
it.

`V43__analytics_counted_facts` creates the ledger and **empties the rollups and the cursor state**:
what was folded before it has no ledger entry and could never be taken back out (and may already hold
the double counts). The next projector pass rebuilds the read model from the raw tables — the ordinary
backfill — recording each contribution as it goes.

What is still **in flight** is not folded. It is small, and `RollupAnalyticsReader` counts it live
(`... WHERE finished IS NULL ...`) and merges it over the terminal rollup at read time. The two are
disjoint — a reopened row's contribution is retracted from the rollup on the next pass — so nothing is
double-counted beyond that pass.

Everything is keyed by the process's **creation day**, because that is what a report selects a window
by; a window is then a sum over the creation-days it covers.

## What is exact and what is not

- **Exact:** counts by status, created/finished per day, sample counts, total durations (all
  additive), and average duration (total ÷ samples).
- **Approximate:** **p95.** A percentile is not additive, so durations are stored as a
  `DurationHistogram` (four buckets per octave, ~19% relative width) that *is* mergeable; the p95 is
  read back off the merged buckets. The estimate never understates the exact value and overstates it
  by at most a bucket — good for a latency panel, not for a billing figure.
- **Window granularity:** day-aligned. A rolling 30-day window includes the whole of the boundary
  day, not the exact hour 30 days ago — the natural resolution of a day-bucketed store.

Eventual consistency: a just-created, just-finished or just-reopened process shows up correctly on the
next fold cycle (seconds), which is the point — the read model updates offline so the report never
waits on it. Between a retry and that cycle, the reopened process is briefly counted both in its old
terminal status and in the live overlay.

Not retracted (nor needed): the `any_name` sample name on a status row and a step's `first_order`
(a minimum; a retried execution keeps its order). A process or step execution that is deleted outright
keeps its counted contribution, as before.

## Correctness

`RollupFolder` and `RollupReducer` are pure. `RollupEquivalenceTest` folds a fixture into rollup rows
and a live overlay, reduces it back, and asserts it equals the shipped in-memory aggregate to the
number on everything additive, and within a bucket on p95. `DurationHistogramTest` covers the
histogram itself. The JPA glue — the cursored source queries, the upserts, the cursor state, the
window queries — is pinned by `AnalyticsReadModelH2Test` and `AnalyticsReadModelPostgresTest` (the
same contract on H2 and, when Docker is available, PostgreSQL), and `AnalyticsRollupJpaE2eTest`
boots the engine with the flag on and reads a report back from the rollups.

The contract also covers the ledger: a retried process, one retried and finished again, a restarted
one, a step execution reset to `CREATED`, a reopen-and-refinish between two passes, retraction
repeated across passes and pods, and a replay of every finish (cursors rewound) leaving the read model
unchanged — each ending equal to a fresh rebuild.
