-- forEach `label`: what the forEach's label expression made of an iteration's item ("Hab. 101"),
-- evaluated once when the iteration is materialised and shown in the console instead of "item N".
-- Sealed like `scope_json` when the process has sensitive variables (the label is made from the item),
-- hence TEXT. Nullable: an iteration without a label, and every row written before, has none.
--
-- Plain portable DDL with IF NOT EXISTS, as V47: it also runs over a schema ddl-auto created from the
-- entity (StepExecutionEntity).

ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS item_label TEXT;
