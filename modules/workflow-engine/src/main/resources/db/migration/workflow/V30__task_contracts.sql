-- Task contracts, versioned: the shape an ACTION step commits to when it references a task by id.
-- Several versions of one id coexist as distinct rows (in-flight processes keep their pinned
-- version while new definitions pick up the latest), so the primary key is the <id>@<version>
-- reference and contract_id is indexed for "the latest version of this id".
--
-- Plain portable DDL with IF NOT EXISTS: this also runs over a schema that ddl-auto already created
-- from TaskContractEntity (the embedded path and the schema-validation harness on H2). The entity
-- mapping and this file must describe the same shape. The contract is kept whole as JSON, read
-- entire with the row, never queried field by field.
CREATE TABLE IF NOT EXISTS ec_task_contract (
    ref            VARCHAR(512) NOT NULL PRIMARY KEY,
    contract_id    VARCHAR(255) NOT NULL,
    version        INTEGER NOT NULL,
    contract_group VARCHAR(255),
    topic          VARCHAR(255),
    contract_json  TEXT
);
CREATE INDEX IF NOT EXISTS idx_task_contract_id ON ec_task_contract (contract_id, version);
