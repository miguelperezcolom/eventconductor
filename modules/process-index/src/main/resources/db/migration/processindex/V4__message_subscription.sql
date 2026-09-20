-- Which shard has a step waiting for a given message/key — the routing half of the fleet database
-- (layer 2 of sharded message routing). Written by each shard's projector when a WAIT_FOR_MESSAGE
-- step starts and stops waiting, read by the router to send a message only to the shard(s) that can
-- correlate it, instead of broadcasting to every shard.
--
-- Keyed by the step execution, so it is idempotent under redelivery and needs no reference count: a
-- shard "is waiting for (name, key)" iff it has at least one row, and the router reads the distinct
-- shards. A stale row (a step that stopped waiting but whose delete has not projected yet) only
-- costs an extra message to a shard that will not match it — never a lost message.
--
-- Not the read model and not derived from it: like process_placement it is authoritative routing
-- state. Rows are removed when the step stops waiting; nothing prunes by time.
CREATE TABLE IF NOT EXISTS message_subscription (
    step_execution_id varchar(255) PRIMARY KEY,
    message_name      varchar(255) NOT NULL,
    correlation_key   varchar(255) NOT NULL,
    shard_id          varchar(255),
    updated_at        timestamp    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_subscription_name_key
    ON message_subscription (message_name, correlation_key);
