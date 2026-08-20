-- The step-execution listing pages in SQL now: it orders by started_at descending and optionally
-- keeps only the ERROR/TIMEOUT rows. This is the engine's largest table, so without an index every
-- page turn was a sequential scan plus a top-N sort of all of it.
--
-- The ordering has to match the query's "order by started_at desc nulls last" exactly, or the
-- planner cannot read the index in order and sorts anyway: started_at is nullable, and a plain
-- DESC index in Postgres is NULLS FIRST.
--
-- "Only errors" is served by the existing idx_step_exec_status, which the count query uses too.

CREATE INDEX IF NOT EXISTS idx_step_exec_started_at
    ON step_execution_entity (started_at DESC NULLS LAST);
