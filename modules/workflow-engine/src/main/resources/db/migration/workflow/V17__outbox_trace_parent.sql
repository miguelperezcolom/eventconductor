-- The W3C traceparent of whatever produced each outbox message.
--
-- This table is the engine's asynchronous boundary: a row is written inside the transaction that
-- produced the event and published by a relay thread some time later. No automatic instrumentation
-- bridges that — it sees a database write in one trace and, afterwards, an unrelated Kafka send —
-- so following one process end to end gave a trace per hop. Carrying the context here lets the
-- relay publish as a continuation of the trace that caused the event.
--
-- Null whenever nothing was being traced, which is the default: tracing is off until an OTLP
-- endpoint and a sampling probability are configured.
--
-- IF NOT EXISTS because this migration also runs over a schema that ddl-auto already created from
-- the entities, where the column is present before Flyway sees it.
ALTER TABLE outbox_message_entity
    ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(64);
