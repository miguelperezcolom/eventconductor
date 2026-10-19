package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The retention purge on the engine's database: plain SQL, every question an index range or a
 * primary-key lookup (V45 adds the two the purge needs), and one transaction per process tree.
 *
 * <h2>What a purged process takes with it</h2>
 *
 * <ul>
 *   <li>{@code process_entity} — the process (its variables, log and reply ride on the row);</li>
 *   <li>{@code step_execution_entity} — its step executions (their variables, and the message
 *       subscriptions of steps that waited: those are columns of the step row);</li>
 *   <li>{@code log_message_entity} — its history;</li>
 *   <li>{@code resource_entity} — the resources its steps recorded;</li>
 *   <li>{@code process_trace_context} — the trace it joined;</li>
 *   <li>{@code sync_invocation} — the synchronous invocation that started it, if any (its idempotency
 *       key is then free, as after the invocation's own retention);</li>
 *   <li>{@code process_update} — its updates (V55), with their results;</li>
 *   <li>{@code analytics_counted_process}, {@code analytics_counted_step} — the analytics projector's
 *       ledger of what it counted for the process and its steps. The daily rollups themselves are
 *       kept: the report still counts the process on the days it ran;</li>
 *   <li>{@code process_index} — its row in the process-index read model, when that lives here.</li>
 * </ul>
 *
 * <p><b>Kept</b>: the rollups; {@code outbox_message_entity} (a purgeable process has only Sent rows,
 * which the outbox's own retention removes); {@code inbound_event_receipt} (keyed by event, not by
 * process, and forgotten by its own retention); {@code process_lock} and {@code process_lock_waiter}
 * (a process with a row in either is not purged at all); definitions, versions, schedules, triggers.
 *
 * <h2>Refusing rather than deleting</h2>
 *
 * <p>The process rows are deleted first, guarded by "still terminal, still has an end time": that
 * takes their row locks — the per-process lock the engine itself takes — and, should an operator have
 * retried one since it was found, deletes fewer rows than the tree has, and the whole transaction is
 * rolled back. Then the tree is checked for anything still at work (a step pending, running, waiting
 * to retry or queued on a lock; a lock held or awaited; an outbox message not yet sent), and again
 * any of it rolls everything back.
 */
@Repository
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JdbcProcessPurgeRepository implements ProcessPurgeRepository {

    /** Bound placeholders per IN list: well under every database's limit, and one round trip per chunk. */
    static final int CHUNK = 500;

    private static final String TERMINAL = ProcessRetention.TERMINAL.stream()
            .map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));

    /** Statuses of a step that is still going: a worker owes an answer, a backoff or a lock will wake it. */
    private static final String STEP_AT_WORK = "'PENDING','RUNNING','AWAITING_RETRY','WAITING_ON_LOCK'";

    private static final RowMapper<PurgeCandidate> CANDIDATE = (rs, n) -> {
        var finished = rs.getTimestamp("finished");
        return new PurgeCandidate(rs.getString("id"), rs.getString("workflow_definition_id"),
                status(rs.getString("status")), finished == null ? null : finished.toLocalDateTime());
    };

    final JdbcTemplate jdbcTemplate;
    final TransactionTemplate transactionTemplate;

    @Override
    public Set<String> knownWorkflowDefinitionIds() {
        // The version history outlives a deleted definition's head row, and is one row per version —
        // never a scan of the processes.
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT definition_id FROM workflow_definition_version", String.class));
    }

    @Override
    public List<PurgeCandidate> findRoots(String workflowDefinitionId, ProcessStatus status,
                                          LocalDateTime finishedBefore, PurgeCandidate after, int limit) {
        var sql = new StringBuilder("""
                SELECT p.id, p.workflow_definition_id, p.status, p.finished FROM process_entity p
                WHERE p.workflow_definition_id = ? AND p.status = ? AND p.finished < ?""");
        var args = new ArrayList<Object>(List.of(workflowDefinitionId, status.name(), Timestamp.valueOf(finishedBefore)));
        if (after != null) {
            sql.append(" AND (p.finished > ? OR (p.finished = ? AND p.id > ?))");
            args.add(Timestamp.valueOf(after.finished()));
            args.add(Timestamp.valueOf(after.finished()));
            args.add(after.id());
        }
        sql.append("""
                 AND (p.parent_step_execution_id IS NULL
                      OR NOT EXISTS (SELECT 1 FROM step_execution_entity s WHERE s.id = p.parent_step_execution_id))
                ORDER BY p.finished, p.id""");
        // Bounded on the statement rather than with LIMIT/FETCH FIRST, which not every supported
        // database spells the same way; a plain SELECT takes no row locks, so nothing beyond the
        // rows read is held.
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(sql.toString());
            ps.setMaxRows(limit);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        }, CANDIDATE);
    }

    @Override
    public List<PurgeCandidate> findChildren(Collection<String> processIds) {
        var children = new ArrayList<PurgeCandidate>();
        for (var chunk : chunks(processIds)) {
            children.addAll(jdbcTemplate.query("""
                    SELECT c.id, c.workflow_definition_id, c.status, c.finished FROM process_entity c
                    WHERE c.parent_step_execution_id IN (SELECT s.id FROM step_execution_entity s
                                                         WHERE s.process_id IN (%s))""".formatted(placeholders(chunk)),
                    CANDIDATE, chunk.toArray()));
        }
        return children;
    }

    @Override
    public boolean purge(List<PurgeCandidate> tree) {
        var ids = tree.stream().map(PurgeCandidate::id).distinct().toList();
        if (ids.isEmpty()) {
            return false;
        }
        var purged = transactionTemplate.execute(tx -> {
            var deleted = updateIn("DELETE FROM process_entity WHERE status IN (" + TERMINAL + ")"
                    + " AND finished IS NOT NULL AND id IN (%s)", ids);
            if (deleted != ids.size() || stillInUse(ids)) {
                tx.setRollbackOnly();
                return false;
            }
            var stepIds = new ArrayList<String>();
            for (var chunk : chunks(ids)) {
                stepIds.addAll(jdbcTemplate.queryForList("SELECT id FROM step_execution_entity WHERE process_id IN ("
                        + placeholders(chunk) + ")", String.class, chunk.toArray()));
            }
            updateIn("DELETE FROM analytics_counted_step WHERE step_execution_id IN (%s)", stepIds);
            updateIn("DELETE FROM step_execution_entity WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM log_message_entity WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM resource_entity WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM process_trace_context WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM sync_invocation WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM process_update WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM analytics_counted_process WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM process_index WHERE process_id IN (%s)", ids);
            // Search attributes (V48): the process's own, and the embedded index's copy.
            updateIn("DELETE FROM process_search_attribute WHERE process_id IN (%s)", ids);
            updateIn("DELETE FROM process_index_search_attribute WHERE process_id IN (%s)", ids);
            return true;
        });
        return Boolean.TRUE.equals(purged);
    }

    /** Anything of these processes still at work: a step, a lock held or queued for, an unsent message. */
    private boolean stillInUse(List<String> ids) {
        return anyIn("SELECT COUNT(*) FROM step_execution_entity WHERE status IN (" + STEP_AT_WORK + ")"
                        + " AND process_id IN (%s)", ids)
                || anyIn("SELECT COUNT(*) FROM process_lock WHERE holder_process_id IN (%s)", ids)
                || anyIn("SELECT COUNT(*) FROM process_lock_waiter WHERE process_id IN (%s)", ids)
                || anyIn("SELECT COUNT(*) FROM outbox_message_entity WHERE status <> 'Sent' AND partition_key IN (%s)", ids);
    }

    private boolean anyIn(String sql, List<String> values) {
        for (var chunk : chunks(values)) {
            var count = jdbcTemplate.queryForObject(sql.formatted(placeholders(chunk)), Long.class, chunk.toArray());
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    private int updateIn(String sql, List<String> values) {
        var total = 0;
        for (var chunk : chunks(values)) {
            total += jdbcTemplate.update(sql.formatted(placeholders(chunk)), chunk.toArray());
        }
        return total;
    }

    private static List<List<String>> chunks(Collection<String> values) {
        var list = List.copyOf(values);
        var chunks = new ArrayList<List<String>>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            chunks.add(list.subList(from, Math.min(list.size(), from + CHUNK)));
        }
        return chunks;
    }

    private static String placeholders(List<String> chunk) {
        return String.join(",", Collections.nCopies(chunk.size(), "?"));
    }

    private static ProcessStatus status(String value) {
        try {
            return value == null ? null : ProcessStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
