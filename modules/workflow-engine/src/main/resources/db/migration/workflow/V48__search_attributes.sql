-- Search attributes (2.30): named values a definition declares in `searchAttributes` — customerId,
-- amount, region — that every process of it is indexed by, so the process list, the REST API, the MCP
-- tools and batch operations can find processes by them.
--
-- 1. workflow_definition_entity.search_attributes_json: the declaration as the file writes it (a JSON
--    object of name -> template or {value, type}). Part of the version hash: a process evaluates
--    the attributes of the version it runs.
--
-- 2. process_search_attribute: one row per (process, attribute), written at the process-save
--    chokepoint whenever the values change. The value sits in the one column its type uses:
--    keyword_value for keyword, text and bool; number_value; date_value. A listing condition is an
--    EXISTS per attribute, answered by the (name, value) indexes. The retention purge deletes the rows
--    with their process.
--
-- 3. process_index_search_attribute: the same for the embedded CQRS process index (V18's
--    process_index lives in this database too); the read database's own V5 creates it there.
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V46: it also runs over a schema ddl-auto created from
-- the entities (WorkflowDefinitionEntity, ProcessSearchAttributeEntity, ProcessIndexSearchAttributeEntity).

ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS search_attributes_json TEXT;

CREATE TABLE IF NOT EXISTS process_search_attribute (
    process_id      VARCHAR(255)     NOT NULL,
    attr_name       VARCHAR(64)      NOT NULL,
    attr_type       VARCHAR(16)      NOT NULL,
    keyword_value   VARCHAR(255),
    number_value    DOUBLE PRECISION,
    date_value      TIMESTAMP,
    PRIMARY KEY (process_id, attr_name)
);

CREATE INDEX IF NOT EXISTS idx_psa_keyword ON process_search_attribute (attr_name, keyword_value);
CREATE INDEX IF NOT EXISTS idx_psa_number  ON process_search_attribute (attr_name, number_value);
CREATE INDEX IF NOT EXISTS idx_psa_date    ON process_search_attribute (attr_name, date_value);

CREATE TABLE IF NOT EXISTS process_index_search_attribute (
    process_id      VARCHAR(255)     NOT NULL,
    attr_name       VARCHAR(64)      NOT NULL,
    attr_type       VARCHAR(16)      NOT NULL,
    keyword_value   VARCHAR(255),
    number_value    DOUBLE PRECISION,
    date_value      TIMESTAMP,
    PRIMARY KEY (process_id, attr_name)
);

CREATE INDEX IF NOT EXISTS idx_pisa_keyword ON process_index_search_attribute (attr_name, keyword_value);
CREATE INDEX IF NOT EXISTS idx_pisa_number  ON process_index_search_attribute (attr_name, number_value);
CREATE INDEX IF NOT EXISTS idx_pisa_date    ON process_index_search_attribute (attr_name, date_value);
