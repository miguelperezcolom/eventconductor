-- Fields that pick from a fixed list (radio, select, combobox, listBox, choice) can now declare
-- their choices as value/label pairs, so a definition says REFUND to the engine and "Refund the
-- guest" to the person filling the form. They are stored as the JSON array the definition declares:
-- options belong to their field, are read and written with it and are never queried on their own,
-- and form_execution_entity already keeps its variables and values this way.
--
-- Existing rows get NULL, which reads back as no options — what every field declared until now.

ALTER TABLE field_entity ADD COLUMN options TEXT;
