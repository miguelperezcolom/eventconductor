-- The relays now claim only the oldest pending row of each partition key (the process id), so a
-- process's events can no longer be split across pods and reach Kafka out of order. For every
-- candidate the claim asks "is there an older Pending row with this key?":
--
--   NOT EXISTS (SELECT 1 FROM outbox_message_entity o2 WHERE o2.partition_key = o.partition_key
--               AND o2.status = 'Pending' AND o2.timestamp <= o.timestamp AND ...)
--
-- which this index answers as one short range probe: two equalities, then the timestamp range.
-- It replaces V33's (partition_key, status), whose every lookup — the inline driver's, the claim's
-- followers — it covers as a prefix, so the outbox keeps the same number of indexes to maintain.
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
