-- The test worker's two tables, stated here because this module runs ddl-auto=validate.
--
-- The engine's schema is built by its own Flyway migrations and validated against its entities,
-- which is the guarantee this module exists to keep. The test worker ships no migrations — it owns
-- these tables outright and creates them with ddl-auto=update in its own application — so a module
-- that validates has to say what they are. Drift is caught immediately and loudly: a column added
-- to an entity and not to this file fails the context at startup.

CREATE TABLE IF NOT EXISTS received_task (
    id                     VARCHAR(255) NOT NULL PRIMARY KEY,
    process_id             VARCHAR(255),
    workflow_definition_id VARCHAR(255),
    step_id                VARCHAR(255),
    task_id                VARCHAR(255),
    received_at            TIMESTAMP,
    attempt                INTEGER,
    source                 VARCHAR(255),
    matched_by             VARCHAR(255),
    outcome                VARCHAR(255),
    duration_ms            BIGINT,
    replied_at             TIMESTAMP,
    note                   VARCHAR(2000),
    request_variables_json VARCHAR(8000),
    scenario_json          VARCHAR(8000)
);

CREATE TABLE IF NOT EXISTS task_override (
    id                      VARCHAR(255) NOT NULL PRIMARY KEY,
    name                    VARCHAR(255),
    workflow_definition_id  VARCHAR(255),
    step_id                 VARCHAR(255),
    task_id                 VARCHAR(255),
    enabled                 BOOLEAN NOT NULL,
    duration_ms             BIGINT,
    outcome                 VARCHAR(255),
    reason                  VARCHAR(2000),
    failures_before_success INTEGER,
    reply_times             INTEGER,
    ignore_cancellation     BOOLEAN NOT NULL,
    variables_json          VARCHAR(8000),
    logs_json               VARCHAR(8000)
);
