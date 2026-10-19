-- Human task assignment and escalation (2.31): who a task is for, how urgent it is, when it is due,
-- and how far it has been escalated.
--
-- The assignee is the existing user_id (whoever holds the task). Candidate users and groups get a table
-- each, one row per name, so the inbox's "offered to me or to one of my groups" is an indexed EXISTS
-- rather than a scan of JSON. Moments are UTC.
--
-- Existing rows keep NULL in every new column, which the engine reads as what they always were: priority
-- 50, no due date, created at an unknown time, never escalated, and not restricted — open to whoever may
-- work on their form.
--
-- Portable DDL (PostgreSQL, H2, MariaDB), like the engine's migrations: one ALTER per column, plain
-- TIMESTAMP/INTEGER/BOOLEAN, CREATE ... IF NOT EXISTS.

ALTER TABLE form_execution_entity ADD COLUMN priority INTEGER;
ALTER TABLE form_execution_entity ADD COLUMN due_date TIMESTAMP;
ALTER TABLE form_execution_entity ADD COLUMN follow_up_date TIMESTAMP;
ALTER TABLE form_execution_entity ADD COLUMN business_key VARCHAR(255);
ALTER TABLE form_execution_entity ADD COLUMN created_at TIMESTAMP;
ALTER TABLE form_execution_entity ADD COLUMN escalation_level INTEGER;
ALTER TABLE form_execution_entity ADD COLUMN escalated_at TIMESTAMP;
ALTER TABLE form_execution_entity ADD COLUMN restricted BOOLEAN;

CREATE TABLE IF NOT EXISTS form_execution_candidate_user (
    form_execution_id  VARCHAR(255) NOT NULL,
    candidate          VARCHAR(255) NOT NULL,
    PRIMARY KEY (form_execution_id, candidate)
);

CREATE TABLE IF NOT EXISTS form_execution_candidate_group (
    form_execution_id  VARCHAR(255) NOT NULL,
    candidate          VARCHAR(255) NOT NULL,
    PRIMARY KEY (form_execution_id, candidate)
);

-- "Offered to this name": the candidate first, so the lookup by a person or a group is an index range.
CREATE INDEX IF NOT EXISTS idx_form_exec_candidate_user  ON form_execution_candidate_user (candidate);
CREATE INDEX IF NOT EXISTS idx_form_exec_candidate_group ON form_execution_candidate_group (candidate);

-- The inbox's sort and filter columns, behind the status every listing filters on.
CREATE INDEX IF NOT EXISTS idx_form_exec_status_priority ON form_execution_entity (status, priority);
CREATE INDEX IF NOT EXISTS idx_form_exec_status_due      ON form_execution_entity (status, due_date);
CREATE INDEX IF NOT EXISTS idx_form_exec_business_key    ON form_execution_entity (business_key);

-- An escalation and a cancellation find their task by its step execution; that lookup had no index.
CREATE INDEX IF NOT EXISTS idx_form_exec_step_execution  ON form_execution_entity (step_execution_id);
