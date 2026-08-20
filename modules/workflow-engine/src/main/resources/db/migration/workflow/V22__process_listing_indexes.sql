-- The process listing pages in SQL now: it orders by created descending and optionally keeps only
-- the ERROR rows. Without these, every page turn was a sequential scan plus a top-N sort of the
-- whole table.
--
-- The ordering has to match the query's "order by created desc nulls last" exactly, or the planner
-- cannot read the index in order and sorts anyway: created is nullable, and a plain DESC index in
-- Postgres is NULLS FIRST.

-- Unfiltered listing: newest first, straight off the index.
CREATE INDEX IF NOT EXISTS idx_process_created
    ON process_entity (created DESC NULLS LAST);

-- "Only errors", and the status counts on the home dashboard.
CREATE INDEX IF NOT EXISTS idx_process_status_created
    ON process_entity (status, created DESC NULLS LAST);
