-- field_entity was keyed on the field id alone, but a field id is unique only *within* its form:
-- form-schema.json says so, and git-imported definitions use human slugs ("approved", "comment")
-- that repeat across forms. Two forms sharing one meant the second save re-parented the row and the
-- first form lost the field. The key becomes (form_id, id).
--
-- Adds field_order in the same pass: fields are an ordered list, and without a stored position a
-- SELECT returns them in whatever order the database likes, so a form could render its fields
-- shuffled. Existing rows get 0 — their original order was never recorded and cannot be recovered.
--
-- Written as a rebuild because dropping a primary key portably needs its constraint name, which
-- differs per database. Postgres, H2 and MariaDB all drop a table's indexes with the table and all
-- support ALTER TABLE ... RENAME TO.

-- The key is added after the rename, not declared here: the backing index would otherwise have to
-- be named while V1's field_entity_pkey still exists, and index names are schema-wide on Postgres.
CREATE TABLE IF NOT EXISTS field_entity_v3 (
    form_id      VARCHAR(255) NOT NULL,
    id           VARCHAR(255) NOT NULL,
    label        VARCHAR(255),
    data_type    VARCHAR(64),
    stereotype   VARCHAR(64),
    required     BOOLEAN NOT NULL DEFAULT FALSE,
    description  VARCHAR(1024),
    field_order  INTEGER NOT NULL DEFAULT 0
);

-- form_id IS NOT NULL: the repository has always set it, so this drops nothing a running system
-- created, and the column cannot be part of the new key while it is nullable.
INSERT INTO field_entity_v3 (form_id, id, label, data_type, stereotype, required, description, field_order)
SELECT form_id, id, label, data_type, stereotype, required, description, 0
FROM field_entity
WHERE form_id IS NOT NULL;

DROP TABLE field_entity;

ALTER TABLE field_entity_v3 RENAME TO field_entity;

ALTER TABLE field_entity ADD CONSTRAINT field_entity_pkey PRIMARY KEY (form_id, id);

-- idx_field_form (V1) is not recreated: the primary key now leads with form_id, so it covers every
-- lookup that index existed for.
