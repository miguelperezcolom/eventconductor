-- Optimistic-locking version on the two aggregates a running process mutates.
--
-- This is the fence for the window Kafka's ownership guarantee does not cover: a consumer group
-- gives a partition to exactly one consumer, but during a rebalance the outgoing pod can still be
-- mid-flight on a record the incoming one has just been handed. A stale writer's update then
-- matches no row at its version and fails, instead of quietly overwriting the new owner's work.
--
-- Existing rows are backfilled to 0 rather than left null: Spring Data reads a null version as
-- "never persisted" and would try to INSERT over a row that is already there.

ALTER TABLE process_entity
    ADD COLUMN IF NOT EXISTS version INTEGER;

ALTER TABLE step_execution_entity
    ADD COLUMN IF NOT EXISTS version INTEGER;

UPDATE process_entity SET version = 0 WHERE version IS NULL;
UPDATE step_execution_entity SET version = 0 WHERE version IS NULL;
