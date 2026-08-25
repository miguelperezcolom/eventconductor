-- The engine's copy of process-index V3.
--
-- process_index is created by two migration sets: this one, for the default deployment where the
-- read model lives in the write database, and processindex/V1 for the standalone read database a
-- remote projector maintains. They have to agree, because the same entity is mapped against both —
-- and when they do not, Hibernate's schema validation says so at startup and the application does
-- not come up. Which is what happened: adding the column to one of them took the whole UI suite
-- down with "missing column [name] in table [process_index]".
--
-- See processindex/V3 for why the column exists and what a null in it means.

ALTER TABLE process_index ADD COLUMN IF NOT EXISTS name TEXT;
