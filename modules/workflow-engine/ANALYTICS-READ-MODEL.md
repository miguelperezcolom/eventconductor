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

`AnalyticsRollupProjector` runs on a daemon thread under one advisory lock (id `111222333`), the same
shape as the engine's timeout scheduler. It folds three streams of **immutable facts**, each behind a
`(timestamp, id)` cursor it advances in the same transaction that applies the batch:

- a process the moment it is **created** → `process_created_daily`
- a process the moment it **finishes** → `process_finished_daily`, `process_status_daily`,
  `process_duration_daily`
- a step the moment it **finishes** → `step_status_daily`, `step_duration_daily`

Because a fact is folded exactly once and the cursor only moves forward, counts are added without
double-counting, and **history is never re-scanned** — steady-state work is a batch or two. The first
run finds the cursors at the epoch and drains all of history in bounded batches: that is the backfill,
no separate path.

What is still **in flight** is not folded (a running process is not an immutable fact). It is small,
and `RollupAnalyticsReader` counts it live (`... WHERE finished IS NULL ...`) and merges it over the
terminal rollup at read time. The two are disjoint, so nothing is double-counted.

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

Eventual consistency: a just-created or just-finished process shows up on the next fold cycle
(seconds), which is the point — the read model updates offline so the report never waits on it.

## Correctness

`RollupFolder` and `RollupReducer` are pure. `RollupEquivalenceTest` folds a fixture into rollup rows
and a live overlay, reduces it back, and asserts it equals the shipped in-memory aggregate to the
number on everything additive, and within a bucket on p95. `DurationHistogramTest` covers the
histogram itself. The JPA glue follows the engine's existing adapter and scheduler patterns.
