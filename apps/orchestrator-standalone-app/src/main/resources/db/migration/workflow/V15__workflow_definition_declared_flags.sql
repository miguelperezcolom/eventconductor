-- What the definition file itself declares about being disabled or archived, kept apart from the
-- runtime flags of the same name.
--
-- They answer to different people. The file answers to whoever writes the workflow and lives in
-- version control; the runtime flags answer to whoever operates it and are a button. Sharing one
-- column meant they overwrote each other: every git import cleared an operator's disable, and an
-- operator could put back into service a workflow whose own definition says it is not to run.
--
-- The declaration is a floor: a workflow is disabled if either says so, and no runtime toggle can
-- lift what the file declares.
--
-- IF NOT EXISTS because this migration also has to run over a schema that ddl-auto already created
-- from the entities, where the columns are present before Flyway ever sees them.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS declared_disabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS declared_archived BOOLEAN NOT NULL DEFAULT FALSE;
