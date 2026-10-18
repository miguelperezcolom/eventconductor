-- The relays keep a process's events in order across pods: after claiming a batch, a relay reads
-- the Pending rows of the claimed partition keys (the process ids) and sends, per key, only the
-- claimed rows that form an unbroken run from that key's oldest Pending row (OutboxKeyOrder).
-- That read is
--
--   SELECT id, partition_key, timestamp FROM outbox_message_entity
--   WHERE status = 'Pending' AND partition_key IN (...) AND timestamp <= ?
--
-- which this index answers as short range probes: two equalities, then the timestamp range.
-- It replaces V33's (partition_key, status), whose every lookup (the inline driver's, the claim's
-- followers) it covers as a prefix, so the outbox keeps the same number of indexes to maintain.
--
-- partition_key itself has been written for every keyed event since V33; no backfill. Rows from
-- before that, and unkeyed events, have NULL, which no equality matches: they are claimed as they
-- always were, ordered against nothing.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V43: it also runs over a schema ddl-auto created
-- from the entity (OutboxMessageEntity, which declares the same index).
CREATE INDEX IF NOT EXISTS idx_outbox_key_status_ts
    ON outbox_message_entity (partition_key, status, timestamp);

DROP INDEX IF EXISTS idx_outbox_partition_status;
