-- Baseline migration for the forms-engine schema.
--
-- Corresponds to the schema Hibernate generates from the JPA entities under
-- modules/forms-engine/src/main/java/io/mateu/workflow/infra/out/persistence
-- using Spring Boot's CamelCaseToUnderscoresNamingStrategy.
--
-- Migration strategy: see the comment in
-- apps/orchestrator-standalone-app/src/main/resources/db/migration/workflow/V1__baseline.sql

CREATE TABLE IF NOT EXISTS form_entity (
    id           VARCHAR(255) PRIMARY KEY,
    name         VARCHAR(255),
    description  VARCHAR(1024)
);

CREATE TABLE IF NOT EXISTS field_entity (
    id           VARCHAR(255) PRIMARY KEY,
    form_id      VARCHAR(255),
    label        VARCHAR(255),
    data_type    VARCHAR(64),
    stereotype   VARCHAR(64),
    required     BOOLEAN NOT NULL DEFAULT FALSE,
    description  VARCHAR(1024)
);

CREATE INDEX IF NOT EXISTS idx_field_form ON field_entity (form_id);

CREATE TABLE IF NOT EXISTS form_execution_entity (
    id                 VARCHAR(255) PRIMARY KEY,
    form_id            VARCHAR(255),
    process_id         VARCHAR(255),
    step_id            VARCHAR(255),
    step_execution_id  VARCHAR(255),
    variables          TEXT,
    "values"           TEXT,
    status             VARCHAR(64),
    user_id            VARCHAR(255),
    user_group         VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_form_exec_process ON form_execution_entity (process_id);
CREATE INDEX IF NOT EXISTS idx_form_exec_status  ON form_execution_entity (status);
