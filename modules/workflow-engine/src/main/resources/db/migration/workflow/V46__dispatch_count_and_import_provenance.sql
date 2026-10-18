-- Two durability gaps closed in 2.28.
--
-- 1. step_execution_entity.dispatch_count: which dispatch of a step execution is the current one.
--    The step execution id stays the same when a step runs again (a process restarted from the top,
--    a step retried after a timeout), so a late reply from the abandoned attempt's worker could not
--    be told from the running attempt's. The number travels with the task and is echoed on the reply;
--    a reply for another number is dropped. NULL on rows written before this column (read as 0), and
--    a reply that carries no attempt is applied unchecked, as before.
--
-- 2. imported_definition_source: which definitions (and forms, rules: `namespace`) each import source
--    — a git repository, a directory — brought in last time, so the next import can archive what the
--    source no longer has. It was held in memory, so after a restart the first import of a source
--    pruned nothing: a definition deleted from the repository while the engine was down stayed ACTIVE,
--    its schedules still firing. The id is a hash of namespace and source, as event_trigger's: a source
--    key can be a long path or URL.
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V44: it also runs over a schema ddl-auto created from
-- the entities (StepExecutionEntity, ImportedDefinitionSourceEntity).

ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS dispatch_count INTEGER;

CREATE TABLE IF NOT EXISTS imported_definition_source (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    namespace   VARCHAR(64)   NOT NULL,
    source_key  VARCHAR(1024) NOT NULL,
    ids_json    TEXT
);
