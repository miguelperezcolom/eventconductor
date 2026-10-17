-- Event triggers declared outside the definitions: the entries of every imported events.yaml, by the
-- source they came from (classpath:/workflows/events.yaml, a directory, a git url), as cron_schedule
-- holds cron.yaml entries. A definition's own `triggers` list rides on its row (triggers_json, V39).
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V39: it also runs over a schema ddl-auto created from
-- the entities (EventTriggerEntity).
CREATE TABLE IF NOT EXISTS event_trigger (
    id                 VARCHAR(64)   NOT NULL PRIMARY KEY,
    source_key         VARCHAR(1024) NOT NULL,
    entry_index        INTEGER       NOT NULL,
    workflow_id        VARCHAR(255)  NOT NULL,
    trigger_id         VARCHAR(160),
    destination        VARCHAR(255)  NOT NULL,
    event_type         VARCHAR(512)  NOT NULL,
    filter_expression  TEXT,
    business_key       TEXT,
    variables_json     TEXT
);

CREATE INDEX IF NOT EXISTS idx_event_trigger_workflow_id ON event_trigger (workflow_id);
