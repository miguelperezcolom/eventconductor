-- A field can now say where its choices come from instead of listing them: a REST endpoint the
-- browser calls as the form renders (mateu's RestDataSource / @RestOptions), so a picker offers
-- what a catalogue says right now rather than what the definition committed to months ago. Stored
-- as the JSON descriptor the definition declares, next to the options it replaces.
--
-- Existing rows get NULL, which reads back as no source — what every field has had until now.

ALTER TABLE field_entity ADD COLUMN options_source TEXT;
