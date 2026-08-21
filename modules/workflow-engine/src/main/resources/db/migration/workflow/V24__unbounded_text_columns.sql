-- Columns that hold as much as their author needed, widened to TEXT.
--
-- These were VARCHAR(1024) and VARCHAR(2048): numbers picked as "surely enough" rather than as a
-- rule anything enforces. A step's variables are a JSON document, a log message carries whatever a
-- worker threw, and a URL is as long as it is. In PostgreSQL there is nothing to be gained by
-- capping them — varchar(n) and text are the same storage and the same speed, and the length is a
-- constraint, not an optimisation. What the cap buys is an INSERT that fails in production.
--
-- The entity mappings are the other half of this, and were the half that actually bit: none of
-- these fields declared a length, so Hibernate mapped them to varchar(255) — narrower than every
-- number here. Any deployment running ddl-auto with the migrations off (the demo and the sagas PoC
-- both do) got 255, and a form execution carrying twenty variables could not be written at all.
-- Both halves now say TEXT, so it no longer matters which one built the schema.
--
-- Widening is not rewriting: ALTER ... TYPE TEXT from a varchar is a metadata-only change in
-- PostgreSQL, with no table rewrite and no lock held for the length of one.

ALTER TABLE step_entity        ALTER COLUMN variables    TYPE TEXT;
ALTER TABLE step_entity        ALTER COLUMN precondition TYPE TEXT;
ALTER TABLE step_entity        ALTER COLUMN description  TYPE TEXT;
ALTER TABLE log_message_entity ALTER COLUMN message      TYPE TEXT;
ALTER TABLE resource_entity    ALTER COLUMN url          TYPE TEXT;
