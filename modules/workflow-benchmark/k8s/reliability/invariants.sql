-- The verdict. Everything the reliability run claims is computed here, from the engine's own
-- tables plus soak_progress, which is the only thing the harness writes.
--
-- Read it as one sentence: the broker acknowledged N creation requests, so the engine must show
-- exactly N processes, each with exactly one row per step of its definition, and — once the load
-- has stopped and the drain window has passed — all of them finished. Anything else is a defect,
-- including anything merely stuck.
--
-- The checks are written so a FAIL is a FAIL regardless of when they are run; the ones that only
-- make sense after the load has drained are labelled, because during a run they are expected to
-- be non-zero and mean nothing.

\pset footer off
\timing off

\echo '== invariants =============================================================='

WITH
handed_over AS (
    SELECT coalesce(sum(acked), 0)     AS acked,
           coalesce(sum(attempted), 0) AS attempted,
           coalesce(sum(failed), 0)    AS failed
    FROM soak_progress
),
soak AS (
    SELECT id, status FROM process_entity WHERE business_key LIKE 'soak-%'
),
present AS (SELECT count(*) AS n FROM soak),
finished AS (SELECT count(*) AS n FROM soak WHERE status = 'COMPLETED'),
live AS (SELECT count(*) AS n FROM soak WHERE status NOT IN ('COMPLETED', 'CANCELLED')),
duplicate_steps AS (
    -- Exactly-once, the invariant Kafka's at-least-once delivery is most likely to break: a
    -- redelivered event that is not idempotent shows up here as a second row for the same step
    -- of the same process.
    SELECT count(*) AS n FROM (
        SELECT s.process_id, s.step_id
        FROM step_execution_entity s JOIN soak p ON p.id = s.process_id
        GROUP BY 1, 2 HAVING count(*) > 1
    ) d
),
unfinished_steps AS (
    SELECT count(*) AS n
    FROM step_execution_entity s JOIN soak p ON p.id = s.process_id
    WHERE s.status NOT IN ('COMPLETED', 'CANCELLED')
),
retried_steps AS (
    -- Not a failure. A step that was attempted more than once is the engine recovering, and
    -- seeing some here after a chaos scenario is the system working.
    SELECT count(*) AS n
    FROM step_execution_entity s JOIN soak p ON p.id = s.process_id
    WHERE s.attempt_count > 1
),
stuck_outbox AS (
    SELECT count(*) AS n FROM outbox_message_entity WHERE status <> 'Sent'
),
errored_outbox AS (
    SELECT count(*) AS n FROM outbox_message_entity WHERE status = 'Error'
)
SELECT * FROM (
    SELECT 1 AS ord, 'creations acknowledged by the broker' AS invariant,
           acked::text AS value, '' AS verdict FROM handed_over
    UNION ALL
    SELECT 2, 'creations the driver failed to hand over',
           failed::text,
           CASE WHEN failed = 0 THEN 'ok' ELSE 'NOTE: conservation is only checked against acked' END
    FROM handed_over
    UNION ALL
    SELECT 3, 'processes in the engine',
           (SELECT n FROM present)::text, ''
    UNION ALL
    SELECT 4, 'CONSERVATION  acked - present  (must be 0)',
           ((SELECT acked FROM handed_over) - (SELECT n FROM present))::text,
           CASE WHEN (SELECT acked FROM handed_over) = (SELECT n FROM present)
                THEN 'PASS' ELSE 'FAIL' END
    UNION ALL
    SELECT 5, 'EXACTLY-ONCE  steps executed twice  (must be 0)',
           (SELECT n FROM duplicate_steps)::text,
           CASE WHEN (SELECT n FROM duplicate_steps) = 0 THEN 'PASS' ELSE 'FAIL' END
    UNION ALL
    SELECT 6, 'processes finished',
           (SELECT n FROM finished)::text, ''
    UNION ALL
    SELECT 7, 'DRAINED  processes still live  (must be 0 after drain)',
           (SELECT n FROM live)::text,
           CASE WHEN (SELECT n FROM live) = 0 THEN 'PASS' ELSE 'in flight / FAIL after drain' END
    UNION ALL
    SELECT 8, 'DRAINED  steps still live  (must be 0 after drain)',
           (SELECT n FROM unfinished_steps)::text,
           CASE WHEN (SELECT n FROM unfinished_steps) = 0 THEN 'PASS' ELSE 'in flight / FAIL after drain' END
    UNION ALL
    SELECT 9, 'DRAINED  outbox not yet sent  (must be 0 after drain)',
           (SELECT n FROM stuck_outbox)::text,
           CASE WHEN (SELECT n FROM stuck_outbox) = 0 THEN 'PASS' ELSE 'in flight / FAIL after drain' END
    UNION ALL
    SELECT 10, 'outbox messages parked as Error  (must be 0)',
           (SELECT n FROM errored_outbox)::text,
           CASE WHEN (SELECT n FROM errored_outbox) = 0 THEN 'PASS' ELSE 'FAIL' END
    UNION ALL
    SELECT 11, 'steps retried (recovery, not a failure)',
           (SELECT n FROM retried_steps)::text, ''
) checks ORDER BY ord;

\echo ''
\echo '== process status =========================================================='
SELECT status, count(*) FROM process_entity WHERE business_key LIKE 'soak-%'
GROUP BY status ORDER BY 2 DESC;

\echo ''
\echo '== steps per process (every finished process must show the same shape) ====='
SELECT steps, count(*) AS processes FROM (
    SELECT p.id, count(s.id) AS steps
    FROM process_entity p LEFT JOIN step_execution_entity s ON s.process_id = p.id
    WHERE p.business_key LIKE 'soak-%' AND p.status = 'COMPLETED'
    GROUP BY p.id
) shapes GROUP BY steps ORDER BY processes DESC;

\echo ''
\echo '== outbox =================================================================='
SELECT status, count(*) FROM outbox_message_entity GROUP BY status ORDER BY 2 DESC;

\echo ''
\echo '== load recorded by each driver instance ==================================='
SELECT prefix, attempted, acked, failed,
       to_char(updated_at - started_at, 'HH24:MI:SS') AS ran_for,
       updated_at
FROM soak_progress ORDER BY started_at;
