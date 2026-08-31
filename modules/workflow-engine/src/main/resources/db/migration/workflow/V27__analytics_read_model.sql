-- The analytics read model: daily rollups the report is summed from, instead of the raw tables it
-- used to scan. The measured problem was a GROUP BY over 2.7M step executions (3.8GB heap) on every
-- page load — 1.2s in the database alone, and O(rows): at ten times the volume it is ten times the
-- wait, because an aggregate that has to touch every row in the window cannot be helped by an index.
--
-- These tables hold that aggregate already reduced. A projector folds each immutable fact — a
-- process that has finished, a step that has finished — into them exactly once, off a cursor, so it
-- never re-scans history; what is still in flight is small and read live at report time and merged
-- on top. A window is then a sum over the creation-days it covers: a few dozen rows, not millions.
--
-- Opt-in (workflow.analytics.rollup=true) and jpa-only. Everything is keyed by the process's
-- CREATION day, because that is what a report selects a window by. Durations carry a serialised
-- DurationHistogram (comma-separated bucket counts) so a p95 can be read back from merged buckets —
-- a percentile is the one figure that is not additive, and a histogram is how it is made to merge.
--
-- IF NOT EXISTS throughout, portable DDL only (no INCLUDE, no Postgres-only types): these migrations
-- also run on H2 for the embedded mode and every test that boots a context, and over a schema
-- ddl-auto may already have created from the entities. TEXT for the histogram, matching how the
-- engine stores its other unbounded columns.

CREATE TABLE IF NOT EXISTS process_created_daily (
    k                       VARCHAR(512) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    created_day             DATE,
    cnt                     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_process_created_daily_day ON process_created_daily (created_day);

CREATE TABLE IF NOT EXISTS process_finished_daily (
    k                       VARCHAR(512) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    created_day             DATE,
    finished_day            DATE,
    cnt                     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_process_finished_daily_created ON process_finished_daily (created_day);

CREATE TABLE IF NOT EXISTS process_status_daily (
    k                       VARCHAR(512) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    created_day             DATE,
    status                  VARCHAR(64),
    cnt                     BIGINT NOT NULL DEFAULT 0,
    any_name                VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_process_status_daily_created ON process_status_daily (created_day);

CREATE TABLE IF NOT EXISTS process_duration_daily (
    k                       VARCHAR(512) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    created_day             DATE,
    samples                 BIGINT NOT NULL DEFAULT 0,
    total_nanos             BIGINT NOT NULL DEFAULT 0,
    histogram               TEXT
);
CREATE INDEX IF NOT EXISTS idx_process_duration_daily_created ON process_duration_daily (created_day);

CREATE TABLE IF NOT EXISTS step_status_daily (
    k                       VARCHAR(768) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    step_id                 VARCHAR(255),
    created_day             DATE,
    status                  VARCHAR(64),
    cnt                     BIGINT NOT NULL DEFAULT 0,
    first_order             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_step_status_daily_created ON step_status_daily (created_day);

CREATE TABLE IF NOT EXISTS step_duration_daily (
    k                       VARCHAR(768) PRIMARY KEY,
    workflow_definition_id  VARCHAR(255),
    step_id                 VARCHAR(255),
    created_day             DATE,
    samples                 BIGINT NOT NULL DEFAULT 0,
    total_nanos             BIGINT NOT NULL DEFAULT 0,
    histogram               TEXT
);
CREATE INDEX IF NOT EXISTS idx_step_duration_daily_created ON step_duration_daily (created_day);

-- One row (id = 1). The projector's place in each stream: the (timestamp, id) of the last process
-- creation, last process finish and last step finish it folded. A fact is folded once — the cursor
-- is strictly greater-than — so counts can be added without double-counting.
CREATE TABLE IF NOT EXISTS analytics_projection_state (
    id                      INTEGER PRIMARY KEY,
    created_cursor_ts       TIMESTAMP,
    created_cursor_id       VARCHAR(255),
    pfinished_cursor_ts     TIMESTAMP,
    pfinished_cursor_id     VARCHAR(255),
    sfinished_cursor_ts     TIMESTAMP,
    sfinished_cursor_id     VARCHAR(255)
);
