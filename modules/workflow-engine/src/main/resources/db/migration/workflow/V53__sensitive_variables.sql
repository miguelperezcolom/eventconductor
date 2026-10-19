-- Sensitive variables (2.31): the variables a definition declares in `sensitiveVariables` (names or
-- globs: cardNumber, "patient.*") — stored and transported only encrypted, masked in the console, REST
-- and MCP. Their values need no new column: a sealed value (ec:enc:v1:<keyId>:<base64>) sits where the
-- plain one did, in process_entity.variables, step_execution_entity.variables, the outbox payload…
--
-- workflow_definition_entity.sensitive_variables_json: the declaration as the file writes it (a JSON
-- array). Part of the version hash: a process protects what the version it runs declares (a step's
-- sensitiveOutputs rides in steps_json).
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V50: it also runs over a schema ddl-auto created from
-- the entity (WorkflowDefinitionEntity).

ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS sensitive_variables_json TEXT;

-- sync_invocation.request_hash: with encryption configured the request fingerprint is a keyed HMAC
-- (h1:<keyId>:<base64url>, see Fingerprints), longer than the 64 hex characters of a plain SHA-256 —
-- which, over a request made of a card number, could be reversed by enumeration.
ALTER TABLE sync_invocation ALTER COLUMN request_hash SET DATA TYPE VARCHAR(255);
