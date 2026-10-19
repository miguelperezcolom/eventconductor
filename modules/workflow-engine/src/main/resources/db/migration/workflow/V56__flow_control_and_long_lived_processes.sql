-- Per-key flow control and long-lived processes (2.33). See FLOW-CONTROL-PLAN.md.
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V50: it also runs over a schema ddl-auto created from
-- the entities. Rolling-upgrade safe: nothing an older engine reads or relies on changes — new tables, and
-- new nullable (or constant-default) columns, which PostgreSQL 11+ adds without rewriting the table. The two
-- indexes on existing, possibly large tables are V56.1's, built CONCURRENTLY on PostgreSQL.
--
-- process_entity.business_key keeps its UNIQUE constraint (V1): it names the run that holds a key NOW. A run
-- that hands its key over to a later one (CONTINUE_AS_NEW, a businessKeyPolicy reuse) moves it to
-- released_business_key in the same transaction, before the later run is inserted. An engine of 2.32 or
-- earlier, running beside this one during a rollout, still finds the one holder by business_key and still has
-- the constraint to fail a duplicate creation on.

-- 1. Declarations: a definition's startPolicy and businessKeyPolicy, a stored trigger's startPolicy.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS start_policy_json TEXT;
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS business_key_policy VARCHAR(40);
ALTER TABLE event_trigger ADD COLUMN IF NOT EXISTS start_policy_json TEXT;

-- 2. The admission state of one flow-control key: id = sha256 of scope and key. Every admission and
-- release takes SELECT ... FOR UPDATE on this row first, which is what serializes two pods deciding for
-- the same key. window_* is the throttle's fixed window; last_* the process the policy started last
-- (what a singleton asks about).
CREATE TABLE IF NOT EXISTS flow_control_key (
    id                 VARCHAR(64)   NOT NULL PRIMARY KEY,
    scope              VARCHAR(512)  NOT NULL,
    flow_key           VARCHAR(1024),
    window_start       TIMESTAMP,
    window_count       INTEGER       NOT NULL DEFAULT 0,
    last_process_id    VARCHAR(255),
    last_business_key  VARCHAR(255),
    last_started_at    TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL
);

-- 3. Starts waiting for their moment: one DEBOUNCE row per key (id derived from the key), any number of
-- THROTTLE rows (FIFO by id: the key and a per-key sequence number). request_json is the start itself.
CREATE TABLE IF NOT EXISTS flow_control_pending (
    id                 VARCHAR(64)   NOT NULL PRIMARY KEY,
    key_id             VARCHAR(64)   NOT NULL,
    kind               VARCHAR(16)   NOT NULL,
    workflow_id        VARCHAR(255)  NOT NULL,
    request_json       TEXT          NOT NULL,
    due_at             TIMESTAMP     NOT NULL,
    enqueued_at        TIMESTAMP     NOT NULL,
    absorbed           INTEGER       NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_flow_control_pending_due ON flow_control_pending (due_at);
CREATE INDEX IF NOT EXISTS idx_flow_control_pending_key ON flow_control_pending (key_id, kind);

-- 4. Worker rate limits: the fixed window of permits per topic (a conditional UPDATE takes one), and the
-- tasks waiting for a permit — their steps stay PENDING, their timeout clock not yet started.
CREATE TABLE IF NOT EXISTS rate_limit_window (
    topic              VARCHAR(255)  NOT NULL PRIMARY KEY,
    window_start       TIMESTAMP     NOT NULL,
    used               INTEGER       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS deferred_dispatch (
    step_execution_id  VARCHAR(255)  NOT NULL PRIMARY KEY,
    process_id         VARCHAR(255)  NOT NULL,
    topic              VARCHAR(255)  NOT NULL,
    priority           INTEGER       NOT NULL DEFAULT 50,
    deferred_at        TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_deferred_dispatch_topic ON deferred_dispatch (topic, priority, deferred_at);

-- 5. Task priority on the HTTP worker queue: claimed ORDER BY priority DESC, created_at (index: V56.1).
ALTER TABLE http_task ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 50;

-- 6. CONTINUE_AS_NEW links between the runs of a chain, and the key of a run that handed it over (index: V56.1).
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS continued_from_process_id VARCHAR(255);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS continued_as_process_id VARCHAR(255);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS released_business_key VARCHAR(255);
