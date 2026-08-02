-- Replace the workflow-definition lifecycle status (DRAFT/ACTIVE/DISABLED/ARCHIVED) with two
-- orthogonal runtime flags. Definitions are now authored as .ec files (git-imported), so the
-- editing lifecycle no longer lives in the definition.
ALTER TABLE workflow_definition_entity ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_definition_entity ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Carry over the meaningful old states: DISABLED -> disabled, ARCHIVED -> archived.
UPDATE workflow_definition_entity SET disabled = TRUE WHERE status = 'DISABLED';
UPDATE workflow_definition_entity SET archived = TRUE WHERE status = 'ARCHIVED';

ALTER TABLE workflow_definition_entity DROP COLUMN status;
ALTER TABLE workflow_definition_entity DROP COLUMN draft_of_id;
