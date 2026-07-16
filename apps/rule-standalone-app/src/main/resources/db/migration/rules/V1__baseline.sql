-- Baseline migration for the rule-engine schema.
--
-- Corresponds to the schema Hibernate generates from the JPA entities under
-- modules/rule-engine/src/main/java/io/mateu/workflow/infra/out/persistence
-- using Spring Boot's CamelCaseToUnderscoresNamingStrategy.
--
-- Migration strategy: see the comment in
-- apps/orchestrator-standalone-app/src/main/resources/db/migration/workflow/V1__baseline.sql

CREATE TABLE IF NOT EXISTS ec_rule (
    id         VARCHAR(255) PRIMARY KEY,
    name       VARCHAR(255),
    type       VARCHAR(64),
    version    INTEGER NOT NULL DEFAULT 0,
    rule_json  TEXT
);
