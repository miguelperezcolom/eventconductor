-- Loops and multi-instance: a step can now have more than one execution in a process.
--
-- A loop (repeat / loopBack) runs a step again as a NEW execution of it: `iteration` numbers the
-- passes (0 = the first run), `superseded` marks a pass a later one replaced, and `loop_decision`
-- latches what the step's loop condition said when it completed (EXITED / LOOPED / EXHAUSTED, or
-- DISCARDED for history a restart left behind).
--
-- A forEach runs a step once per item: the step's execution is a container and each item gets a
-- child execution — `parent_execution_id` is its container, `item_index` its position,
-- `scope_json` the variables laid over the process for it (the item and its index), `output_json`
-- what it reported (an iteration never writes the process's variables). A container keeps its
-- iteration count in `scope_json` and its collected output in `output_json`.
--
-- Every column is nullable: an existing row reads as the first pass of an ordinary step, which is
-- exactly what it is. Nothing is queried by these columns across processes — a process's executions
-- are always loaded together — so no index is added.
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V46: it also runs over a schema ddl-auto created from
-- the entity (StepExecutionEntity).

ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS iteration INTEGER;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS superseded BOOLEAN;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS loop_decision VARCHAR(16);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS parent_execution_id VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS item_index INTEGER;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS scope_json TEXT;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS output_json TEXT;
