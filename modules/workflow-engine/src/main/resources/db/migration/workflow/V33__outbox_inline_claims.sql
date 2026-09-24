-- Synchronous invocation, part 3: the fast path. The pod that creates a process for a synchronous
-- caller handles that process's outbox rows itself instead of waiting for the relay. The rows it
-- writes while driving are inserted already claimed (status 'InlineClaimed', claimed_by, a renewed
-- claim_until) so no relay — which only claims 'Pending' — races for them; a lapsed claim goes back to
-- 'Pending' (InlineClaimSweeper). partition_key lets the driver find one process's rows without
-- reading payloads.
--
-- Plain portable DDL, as V29-V32: it also runs over a schema ddl-auto created from the entity.
ALTER TABLE outbox_message_entity ADD COLUMN IF NOT EXISTS partition_key VARCHAR(255);
ALTER TABLE outbox_message_entity ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(64);
ALTER TABLE outbox_message_entity ADD COLUMN IF NOT EXISTS claim_until TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_outbox_partition_status ON outbox_message_entity (partition_key, status);
CREATE INDEX IF NOT EXISTS idx_outbox_claim_until ON outbox_message_entity (status, claim_until);
