-- What a child process's templates and guards inherit from the PROCESS step that started it:
--   inherited_business_key — an inline body's child reads the business key of the process that owns the
--                            body as businessKey (its own stays parent:<id>);
--   structured_variables   — a forEach child reads the variables that came from the iteration's scope
--                            (its as / indexAs) as structure, so room.number reads in templates.
-- Null for every other process, and for every row written before.
--
-- Plain portable DDL with IF NOT EXISTS, as V47: it also runs over a schema ddl-auto created from the
-- entity (ProcessEntity).

ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS inherited_business_key VARCHAR(255);
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS structured_variables VARCHAR(1024);
