-- The process's own name, so the operator listing can be answered from the read model.
--
-- Without it the index could serve every column the listing shows except the one people read, and
-- searching would find by business key but not by name — a listing that behaves differently
-- depending on which store answered it, which is worse than one that is merely limited.
--
-- Nullable, and it stays null on rows projected before this: ProcessStatusChanged did not carry a
-- name until 2.7.1, so an index built by an older projector has none. Rows fill in as their
-- processes next change status; a backfill is the way to fill them sooner. The listing falls back
-- to the business key rather than rendering a blank.

ALTER TABLE process_index ADD COLUMN IF NOT EXISTS name TEXT;
