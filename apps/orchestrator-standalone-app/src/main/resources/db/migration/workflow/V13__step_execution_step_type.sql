-- The step's type, lifted out of step_json so it can be queried.
--
-- The stalled-step gauge counts live steps that have no deadline, because nothing will ever time
-- those out. Whether that is a fault depends entirely on the kind of step: an ACTION or a RULE is
-- owed an answer by a worker, while a USER_TASK waits for a person and a WAIT_FOR_MESSAGE waits
-- by definition. Without this column the count could not tell them apart, so every deployment
-- with human tasks reported permanent stalled work.
--
-- Left NULL for rows written before this column existed. Nothing backfills it: the type is inside
-- a JSON document and no portable SQL reads that, and the count treats NULL as "unknown, count
-- it" rather than risk silence about steps already in flight through the upgrade. Rows written
-- from here on carry it.
--
-- IF NOT EXISTS because this migration also has to run over a schema that ddl-auto already
-- created from the entities, where the column is present before Flyway ever sees it.
ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS step_type VARCHAR(255);
