-- The forms half of workflow's V24, and the half a real deployment reported.
--
-- form_execution_entity.variables and "values" were already TEXT here, but the entity mapping
-- declared no length, so Hibernate built them as varchar(255) wherever it built the schema itself.
-- A form execution carrying a saga's variables is several kilobytes of JSON, and the insert failed
-- outright:
--
--   value too long for type character varying(255)
--
-- so the task never reached the person it was waiting for, and the step timed out instead. The
-- mappings now say TEXT; these two descriptions are widened to match, for the same reason the
-- others were — a cap chosen as "surely enough" is a production failure waiting for a longer input.

ALTER TABLE form_entity  ALTER COLUMN description TYPE TEXT;
ALTER TABLE field_entity ALTER COLUMN description TYPE TEXT;
