-- Workers over HTTP (2.30): the durable queue of tasks served to workers that long-poll
-- /workflow/api/workers/activate instead of consuming a Kafka topic.
--
-- One row per step execution currently owed an HTTP worker (id = the step execution id); the row is
-- deleted when the worker answers. A newer dispatch of the same step (a retry, a restart: a higher
-- attempt) replaces the row. lease_until/worker_id/lease_token are the lease: a row is available when
-- it is not cancelled and lease_until is NULL or past, so an expired lease is a redelivery with no
-- sweeper. Claimed with SELECT ... ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED (DbLockDialect).
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V46: it also runs over a schema ddl-auto created
-- from the entity (HttpTaskEntity).
CREATE TABLE IF NOT EXISTS http_task (
    id                      VARCHAR(255) NOT NULL PRIMARY KEY,
    process_id              VARCHAR(255),
    workflow_definition_id  VARCHAR(255),
    step_id                 VARCHAR(255),
    task_id                 VARCHAR(255),
    topic                   VARCHAR(255) NOT NULL,
    variables_json          TEXT,
    attempt                 INTEGER,
    created_at              TIMESTAMP    NOT NULL,
    lease_until             TIMESTAMP,
    worker_id               VARCHAR(255),
    lease_token             VARCHAR(64),
    deliveries              INTEGER      NOT NULL DEFAULT 0,
    cancelled               BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_http_task_topic_created ON http_task (topic, created_at);
CREATE INDEX IF NOT EXISTS idx_http_task_lease_until ON http_task (lease_until);
