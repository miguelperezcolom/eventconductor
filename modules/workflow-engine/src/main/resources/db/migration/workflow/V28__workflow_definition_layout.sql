-- Where each step's node sits on the diagram, as the author arranged it, keyed by step id.
--
-- Presentation and nothing else: no engine code reads these coordinates. The column exists because
-- the console draws its graphs from the engine rather than from whoever's working copy, so a layout
-- the database did not carry would be a layout only its author ever saw. It arrives in the .ec file
-- as `layout` and is stored as the same JSON object.
--
-- Nullable with no default, unlike max_steps above it: absent means nobody arranged this workflow,
-- and its graph is laid out automatically — which is exactly what every row written before this
-- column existed should keep doing. An empty object would mean something different and is never
-- written. IF NOT EXISTS because this migration also has to run over a schema that ddl-auto already
-- created from the entities. Plain portable DDL: the schema-validation harness applies every
-- migration on H2.
ALTER TABLE workflow_definition_entity
    ADD COLUMN IF NOT EXISTS layout_json TEXT;
