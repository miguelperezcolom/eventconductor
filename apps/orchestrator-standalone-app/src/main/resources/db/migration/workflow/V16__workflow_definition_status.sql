-- One word each for what the definition declares and what an operator decided, replacing the four
-- booleans that said the same thing less clearly.
--
-- Two booleans can say four things when only three are meaningful, and left "is an archived
-- workflow also disabled?" to be settled in prose. A workflow is open for business when both the
-- file and the runtime say ACTIVE; otherwise the stricter of the two wins.
--
-- The backfill reads the old columns, so they have to exist for these statements to parse — and on
-- a schema ddl-auto built from today's entities they never did. Adding them empty first makes the
-- backfill match nothing there and leaves it untouched where it holds data, the same shape V11
-- needed. All of it is idempotent: this migration also runs over a ddl-auto schema.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS declared_status VARCHAR(32);
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS runtime_status VARCHAR(32);

ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS disabled BOOLEAN DEFAULT FALSE;
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS declared_disabled BOOLEAN DEFAULT FALSE;
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS declared_archived BOOLEAN DEFAULT FALSE;

UPDATE workflow_definition_entity
   SET declared_status = CASE WHEN declared_archived THEN 'ARCHIVED'
                              WHEN declared_disabled THEN 'DISABLED'
                              ELSE 'ACTIVE' END
 WHERE declared_status IS NULL;

UPDATE workflow_definition_entity
   SET runtime_status = CASE WHEN archived THEN 'ARCHIVED'
                             WHEN disabled THEN 'DISABLED'
                             ELSE 'ACTIVE' END
 WHERE runtime_status IS NULL;

ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS disabled;
ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS archived;
ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS declared_disabled;
ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS declared_archived;
