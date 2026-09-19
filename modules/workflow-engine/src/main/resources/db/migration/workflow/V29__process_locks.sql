-- Named per-key serialization locks: the held lock, its FIFO wait queue, and the columns a
-- waiting step carries so it survives a restart and the lease reaper can find it.
--
-- Plain portable DDL with IF NOT EXISTS throughout: this migration also runs over a schema that
-- ddl-auto already created from the entities (ProcessLockEntity, ProcessLockWaiterEntity,
-- StepExecutionEntity), which is what the embedded path and the schema-validation harness on H2
-- do. The entity mappings and this file must describe the same shape.

-- The held lock. The primary key IS the mutex: id = lockName + '\0' + lockKey, so a second holder
-- of the same key fails the insert.
CREATE TABLE IF NOT EXISTS process_lock (
    id                        VARCHAR(512) NOT NULL PRIMARY KEY,
    lock_name                 VARCHAR(255) NOT NULL,
    lock_key                  VARCHAR(255) NOT NULL,
    holder_process_id         VARCHAR(255),
    holder_step_execution_id  VARCHAR(255),
    acquired_at               TIMESTAMP,
    lease_deadline_at         TIMESTAMP
);
-- releaseAll(processId) and terminal-state cleanup list every lock a process holds.
CREATE INDEX IF NOT EXISTS idx_process_lock_holder ON process_lock (holder_process_id);
-- The lease reaper scans for holds whose deadline has passed.
CREATE INDEX IF NOT EXISTS idx_process_lock_lease ON process_lock (lease_deadline_at);

-- The wait queue. The earliest row for a key (by enqueued_at) is admitted next: FIFO.
CREATE TABLE IF NOT EXISTS process_lock_waiter (
    id                 VARCHAR(255) NOT NULL PRIMARY KEY,
    lock_name          VARCHAR(255) NOT NULL,
    lock_key           VARCHAR(255) NOT NULL,
    process_id         VARCHAR(255) NOT NULL,
    step_execution_id  VARCHAR(255),
    enqueued_at        TIMESTAMP NOT NULL
);
-- The release path pops the earliest waiter for a key; the ordering has to be covered too.
CREATE INDEX IF NOT EXISTS idx_process_lock_waiter_queue ON process_lock_waiter (lock_name, lock_key, enqueued_at);
-- releaseAll(processId) drops this process from every queue it waits in.
CREATE INDEX IF NOT EXISTS idx_process_lock_waiter_process ON process_lock_waiter (process_id);

-- A step parked in WAITING_ON_LOCK remembers what it waits on, so re-entry after a restart is
-- idempotent and the reaper can find it. Nullable: only lock steps ever set these.
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS lock_name VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS lock_key VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_step_exec_lock ON step_execution_entity (lock_name, lock_key, status);

-- The process-level lock a definition declares, stored whole as JSON ({name, key}) — read with the
-- definition, never queried — the same way layout_json is.
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS process_lock_json TEXT;
