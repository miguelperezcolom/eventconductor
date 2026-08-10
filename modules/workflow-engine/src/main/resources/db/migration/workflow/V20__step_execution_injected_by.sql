-- The DYNAMIC step execution that injected this step at runtime, or NULL for a step created the
-- ordinary way from the definition at process creation.
--
-- The exact idempotency key for runtime step injection: a re-delivered StepsInjected finds the
-- children it already created marked with the injecting step's id and injects nothing more. Before
-- this column the check was structural (a later-ordered step preconditioned on the DYNAMIC one),
-- which was a heuristic; the marker makes it exact. It also records provenance — which steps a
-- running process grew that its definition never declared — for the graph and tooling to read.
--
-- Left NULL for rows written before this column existed; nothing backfills it, and a NULL simply
-- means "not injected", which is the truth for every step created before injection existed.
--
-- IF NOT EXISTS because this migration also has to run over a schema that ddl-auto already created
-- from the entities, where the column is present before Flyway ever sees it. Plain portable DDL:
-- the schema-validation harness applies every migration on H2.
ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS injected_by_step_execution_id VARCHAR(255);
