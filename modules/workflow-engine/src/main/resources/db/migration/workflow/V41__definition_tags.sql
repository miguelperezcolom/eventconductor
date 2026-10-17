-- Definition tags: short labels for finding and grouping definitions, and the processes of those
-- definitions, in the console. The definition's `tags` list rides on its row as a JSON array, like
-- its `schedules` and `triggers`. Metadata: not part of the version hash.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V40: it also runs over a schema ddl-auto created
-- from the entities (WorkflowDefinitionEntity).
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS tags_json TEXT;
