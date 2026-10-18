-- The analytics read model's ledger of what it has counted, so a counted fact can be taken back out.
--
-- V27's projector treated a finish as an immutable fact: folded once, off a cursor, never looked at
-- again. It is not immutable. RetryProcessUseCase and RestartProcessUseCase reopen a finished process
-- (finished = NULL) and reset its step executions (finished_at = NULL), and the engine's own retry
-- path does the same to a step. The first finish stayed in the rollups, the live overlay counted the
-- reopened row again while it ran, and its next finish was folded on top: one process, counted two or
-- three times.
--
-- These two tables hold, per process and per step execution, the contribution the projector added to
-- the rollups — exactly the fields a rollup key and its additive values are made of. With them the
-- projector can subtract a contribution when the row reopens (its finish was cleared) and swap it for
-- the new one when the row finishes again. One narrow row per finished fact; read only by primary key
-- or joined from the in-flight rows, never scanned.
--
-- Portable DDL, IF NOT EXISTS, as V27.

CREATE TABLE IF NOT EXISTS analytics_counted_process (
    process_id              VARCHAR(255) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    created_day             DATE,
    finished_day            DATE,
    status                  VARCHAR(64),
    duration_nanos          BIGINT
);

CREATE TABLE IF NOT EXISTS analytics_counted_step (
    step_execution_id       VARCHAR(255) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    step_id                 VARCHAR(255),
    created_day             DATE,
    status                  VARCHAR(64),
    step_order              BIGINT NOT NULL DEFAULT 0,
    duration_nanos          BIGINT
);

-- What was folded before this migration has no ledger, so it could never be taken back out — and it
-- may already hold the double counts this fixes. The read model is derived: empty it and drop the
-- cursors, and the projector's first pass rebuilds it from the raw tables (the same backfill a fresh
-- deployment runs), this time recording each contribution as it adds it. Until that pass lands the
-- report reads low for one fold cycle per bounded slice of history.
DELETE FROM process_created_daily;
DELETE FROM process_finished_daily;
DELETE FROM process_status_daily;
DELETE FROM process_duration_daily;
DELETE FROM step_status_daily;
DELETE FROM step_duration_daily;
DELETE FROM analytics_projection_state;
