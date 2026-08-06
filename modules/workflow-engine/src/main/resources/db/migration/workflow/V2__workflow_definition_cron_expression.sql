-- Cron-scheduled process starts: an ACTIVE definition with a cron expression
-- gets a new process instance created automatically at each occurrence.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS cron_expression VARCHAR(255);
