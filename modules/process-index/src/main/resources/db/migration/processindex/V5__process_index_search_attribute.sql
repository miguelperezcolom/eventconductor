-- Search attributes (2.30) in the read database: the named values a definition declares in
-- `searchAttributes`, projected from ProcessStatusChanged with the rest of the row, so the fleet-wide
-- listing can be narrowed by them (customerId = C-9, amount >= 100).
--
-- One row per (process, attribute). The value sits in the one column its type uses: keyword_value for
-- keyword, text and bool; number_value; date_value. A condition is an EXISTS per attribute, answered
-- by the (name, value) indexes below. The engine's own V48 creates the same table in the write
-- database, where the embedded index lives.
--
-- IF NOT EXISTS throughout, like the rest of this migration set.
CREATE TABLE IF NOT EXISTS process_index_search_attribute (
    process_id      varchar(255)     NOT NULL,
    attr_name       varchar(64)      NOT NULL,
    attr_type       varchar(16)      NOT NULL,
    keyword_value   varchar(255),
    number_value    double precision,
    date_value      timestamp,
    PRIMARY KEY (process_id, attr_name)
);

CREATE INDEX IF NOT EXISTS idx_pisa_keyword ON process_index_search_attribute (attr_name, keyword_value);
CREATE INDEX IF NOT EXISTS idx_pisa_number  ON process_index_search_attribute (attr_name, number_value);
CREATE INDEX IF NOT EXISTS idx_pisa_date    ON process_index_search_attribute (attr_name, date_value);
