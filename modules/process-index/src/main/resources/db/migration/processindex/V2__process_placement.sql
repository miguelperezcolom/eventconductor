-- Where each business key is placed. The synchronous half of the fleet database: written by the
-- ingress router before a creation is published, read by nothing else.
--
-- Not part of the read model, and not derived from it. The index is a projection and can be rebuilt
-- by replaying the compacted projection topic; this table cannot, and is backed up and restored like
-- a write database. They live here together for operational convenience only.
--
-- RETENTION IS A TRAP, so the default is not to prune. A row must outlive the window in which a
-- duplicate creation can still arrive — Kafka's retention on the creation path, and the cron window
-- for cron's deterministic per-occurrence keys. Pruned early, a late redelivery is placed fresh on
-- another shard, where the per-shard guard cannot see the original: exactly the duplicate this table
-- exists to prevent, reintroduced by housekeeping. It is two short strings and a timestamp per
-- process; prune it deliberately, when the size is a real problem, and never before the process is.
CREATE TABLE IF NOT EXISTS process_placement (
    business_key varchar(255) PRIMARY KEY,
    shard_id     varchar(255) NOT NULL,
    claimed_at   timestamp    NOT NULL
);
