-- Replace the workflow-definition lifecycle status (DRAFT/ACTIVE/DISABLED/ARCHIVED) with two
-- orthogonal runtime flags. Definitions are now authored as .ec files (git-imported), so the
-- editing lifecycle no longer lives in the definition.
--
-- Written to run over either shape of the table, because it has to: on a schema Flyway built from
-- V1 the old columns are there, and on one ddl-auto built from the entities they never existed
-- and the new ones are already present. The second is the upgrade the deployment guide
-- recommends — Flyway used to be off by default, and the indexes only come from these
-- migrations — and this migration used to fail on it outright, taking the application's startup
-- with it. DdlAutoToFlywayUpgradeTest holds that path open.
--
-- Editing an already-applied migration changes its checksum: a database where this one ran
-- successfully needs a one-off `flyway repair` before it will start again. See the CHANGELOG.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;

-- The carry-over below reads `status`, and a statement naming a column that does not exist fails
-- when it is parsed, not when it matches a row — so on a schema that never had one there is
-- nothing to guard with. Adding it back empty makes the carry-over match nothing there, and
-- leaves it untouched where it does hold data. Both shapes drop it again three lines down.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS status VARCHAR(64);

-- Carry over the meaningful old states: DISABLED -> disabled, ARCHIVED -> archived.
UPDATE workflow_definition_entity SET disabled = TRUE WHERE status = 'DISABLED';
UPDATE workflow_definition_entity SET archived = TRUE WHERE status = 'ARCHIVED';

ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS status;
ALTER TABLE workflow_definition_entity DROP COLUMN IF EXISTS draft_of_id;
