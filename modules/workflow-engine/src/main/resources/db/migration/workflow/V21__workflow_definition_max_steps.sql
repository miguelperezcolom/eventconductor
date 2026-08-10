-- Per-definition cap on total step executions per process instance, runtime injections included.
--
-- A per-definition override of the engine-wide workflow.dynamic.max-steps-per-process guard: a
-- process of this definition may hold at most this many step executions, definition steps plus any
-- injected by a DYNAMIC step (see InjectStepsUseCase). 0 — the default — means "no per-definition
-- override": the effective cap falls back to the global config default.
--
-- DEFAULT 0 so rows written before this column existed read as "fall back to the global default",
-- which is the behaviour they had. IF NOT EXISTS because this migration also has to run over a
-- schema that ddl-auto already created from the entities. Plain portable DDL: the schema-validation
-- harness applies every migration on H2.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS max_steps INTEGER DEFAULT 0 NOT NULL;
