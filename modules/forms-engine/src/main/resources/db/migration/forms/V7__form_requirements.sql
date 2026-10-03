-- A form's requiredScopes / requiredRoles, stored. Until now the JPA repository had nowhere to put
-- them and dropped them on save: with forms.persistence=jpa a form declaring `requiredRoles: [admin]`
-- was anyone's — TaskAuthorization saw no requirement, and HumanTaskChanged announced none. JSON
-- arrays; null is none, which is what every existing form had in effect.

ALTER TABLE form_entity ADD COLUMN required_scopes TEXT;
ALTER TABLE form_entity ADD COLUMN required_roles TEXT;
