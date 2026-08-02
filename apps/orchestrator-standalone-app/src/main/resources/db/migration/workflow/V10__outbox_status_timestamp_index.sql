-- The relays claim pending messages oldest first — "WHERE status = 'Pending' ORDER BY timestamp
-- LIMIT n FOR UPDATE SKIP LOCKED" — so the index has to cover the ordering too, not just the
-- status. Without the timestamp the claim sorts the whole pending set on every pass, and with
-- every pod now relaying (no leader) that happens far more often than it used to.
--
-- Replaces the status-only index, redundant now that the composite covers its prefix.

CREATE INDEX IF NOT EXISTS idx_outbox_status_ts ON outbox_message_entity (status, timestamp);

DROP INDEX IF EXISTS idx_outbox_status;
