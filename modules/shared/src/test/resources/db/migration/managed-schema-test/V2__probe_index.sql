-- The kind of statement that only ever comes from a migration: ddl-auto=update emits no index DDL.
CREATE INDEX IF NOT EXISTS idx_managed_schema_probe ON managed_schema_probe (id);
