-- Baseline migration for the workflow-engine schema.
--
-- This file exists so Flyway has a starting point. The actual table layout
-- below corresponds to the schema Hibernate generates from the JPA entities
-- under modules/workflow-engine/src/main/java/io/mateu/workflow/infra/out/persistence
-- using Spring Boot's CamelCaseToUnderscoresNamingStrategy.
--
-- Migration strategy:
--   * Existing deployments (already running with ddl-auto=update) should set
--     SECURITY_ENABLED=false-or-FLYWAY_ENABLED=false the first time the new
--     code runs, then re-enable Flyway. baseline-on-migrate=true marks the
--     current schema as already at V1.
--   * Fresh deployments will execute this script. Any subsequent schema
--     change must be added as a new V2__*.sql, V3__*.sql, etc. file.
--
-- Tables are created with IF NOT EXISTS so this script is also safe to apply
-- against a partially-existing schema during the transition.

CREATE TABLE IF NOT EXISTS workflow_definition_entity (
    id                            VARCHAR(255) PRIMARY KEY,
    name                          VARCHAR(255),
    version                       INTEGER NOT NULL,
    description                   VARCHAR(255),
    status                        VARCHAR(64),
    draft_of_id                   VARCHAR(255),
    steps_json                    TEXT,
    limit_concurrent_executions   BOOLEAN NOT NULL DEFAULT FALSE,
    max_concurrent_executions     INTEGER NOT NULL DEFAULT 0,
    enqueue_on_limit              BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS step_entity (
    id                            VARCHAR(255) PRIMARY KEY,
    workflow_definition_id        VARCHAR(255),
    type                          VARCHAR(64),
    precondition                  VARCHAR(1024),
    name                          VARCHAR(255),
    description                   VARCHAR(1024),
    variables                     VARCHAR(2048),
    rollbackable                  BOOLEAN NOT NULL DEFAULT FALSE,
    timeout                       BIGINT NOT NULL DEFAULT 0,
    retries                       INTEGER NOT NULL DEFAULT 0,
    compensation_step_id          VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS process_entity (
    id                            VARCHAR(255) PRIMARY KEY,
    business_key                  VARCHAR(255) UNIQUE,
    name                          VARCHAR(255),
    variables                     TEXT,
    status                        VARCHAR(64),
    completion_percentage         INTEGER NOT NULL DEFAULT 0,
    log                           TEXT,
    workflow_definition_id        VARCHAR(255),
    workflow_definition_version   INTEGER NOT NULL DEFAULT 0,
    workflow_definition_json      TEXT,
    created                       TIMESTAMP,
    started                       TIMESTAMP,
    finished                      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_process_status ON process_entity (status);
CREATE INDEX IF NOT EXISTS idx_process_wf_def ON process_entity (workflow_definition_id);

CREATE TABLE IF NOT EXISTS step_execution_entity (
    id                            VARCHAR(255) PRIMARY KEY,
    process_id                    VARCHAR(255),
    workflow_definition_id        VARCHAR(255),
    step_id                       VARCHAR(255),
    step_json                     TEXT,
    variables                     TEXT,
    status                        VARCHAR(64),
    worker_id                     VARCHAR(255),
    _order                        BIGINT NOT NULL DEFAULT 0,
    started_at                    TIMESTAMP,
    attempt_count                 INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_step_exec_process ON step_execution_entity (process_id);
CREATE INDEX IF NOT EXISTS idx_step_exec_status  ON step_execution_entity (status);

CREATE TABLE IF NOT EXISTS outbox_message_entity (
    id            VARCHAR(255) PRIMARY KEY,
    timestamp     TIMESTAMP,
    status        VARCHAR(64),
    message_type  VARCHAR(512),
    payload       TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_message_entity (status);

CREATE TABLE IF NOT EXISTS log_message_entity (
    id                 VARCHAR(255) PRIMARY KEY,
    timestamp          TIMESTAMP,
    process_id         VARCHAR(255),
    step_execution_id  VARCHAR(255),
    message_type       VARCHAR(64),
    message            VARCHAR(2048),
    worker_id          VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_log_process ON log_message_entity (process_id);

CREATE TABLE IF NOT EXISTS resource_entity (
    id                 VARCHAR(255) PRIMARY KEY,
    timestamp          TIMESTAMP,
    process_id         VARCHAR(255),
    step_execution_id  VARCHAR(255),
    type               VARCHAR(64),
    name               VARCHAR(255),
    url                VARCHAR(1024)
);
