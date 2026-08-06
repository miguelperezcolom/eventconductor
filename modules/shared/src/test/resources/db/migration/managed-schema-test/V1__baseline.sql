-- Stand-in for an engine's baseline. IF NOT EXISTS, like every real V1, so applying it to a schema
-- something else already built is a no-op rather than a failed startup.
CREATE TABLE IF NOT EXISTS managed_schema_probe (
    id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);
