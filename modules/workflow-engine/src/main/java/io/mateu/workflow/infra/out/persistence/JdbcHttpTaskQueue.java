package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.httpworkers.HttpTask;
import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.dtos.Variable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The HTTP worker queue in the engine's database ({@code http_task}, V50), shared by every node.
 *
 * <p>Plain JDBC because the claim is the outbox's: {@code SELECT … FOR UPDATE SKIP LOCKED} for the
 * oldest available rows ({@link DbLockDialect#claimHttpTasksSql}), then one update per row stamping
 * the lease, all in one short transaction — so two nodes' claims never return the same task and
 * neither waits for the other.
 *
 * <p>{@link #offer} runs in whatever transaction the caller has — the outbox relay's, in JPA mode —
 * and never relies on a failing statement (an aborted statement poisons a PostgreSQL transaction):
 * it replaces an older dispatch with an update, and inserts only when there is no row.
 */
public class JdbcHttpTaskQueue implements HttpTaskQueue {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Variable>> VARIABLES = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, process_id, workflow_definition_id, step_id, task_id, topic, "
            + "variables_json, attempt, created_at, lease_until, worker_id, lease_token, deliveries, cancelled, priority";

    private final JdbcTemplate jdbc;
    private final DbLockDialect dialect;
    private final TransactionTemplate transactions;

    public JdbcHttpTaskQueue(JdbcTemplate jdbc, DbLockDialect dialect, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.transactions = transactions;
    }

    @Override
    public boolean offer(HttpTask task) {
        var variables = write(task.variables());
        var replaced = jdbc.update("UPDATE http_task SET process_id = ?, workflow_definition_id = ?, step_id = ?, "
                        + "task_id = ?, topic = ?, variables_json = ?, attempt = ?, created_at = ?, lease_until = NULL, "
                        + "worker_id = NULL, lease_token = NULL, deliveries = 0, cancelled = ?, priority = ? "
                        + "WHERE id = ? AND COALESCE(attempt, 0) < ?",
                task.processId(), task.workflowDefinitionId(), task.stepId(), task.taskId(), task.topic(), variables,
                task.attempt(), Timestamp.valueOf(task.createdAt()), false, task.priority(), task.id(), task.attemptOrZero());
        if (replaced > 0) {
            return true;
        }
        var present = jdbc.queryForObject("SELECT COUNT(*) FROM http_task WHERE id = ?", Long.class, task.id());
        if (present != null && present > 0) {
            return false;
        }
        jdbc.update("INSERT INTO http_task (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, ?, ?)",
                task.id(), task.processId(), task.workflowDefinitionId(), task.stepId(), task.taskId(), task.topic(),
                variables, task.attempt(), Timestamp.valueOf(task.createdAt()), false, task.priority());
        return true;
    }

    @Override
    public List<HttpTask> claim(Collection<String> topics, int max, String workerId, LocalDateTime now,
                                LocalDateTime leaseUntil) {
        if (topics.isEmpty() || max <= 0) {
            return List.of();
        }
        var result = transactions.execute(status -> {
            var args = new ArrayList<Object>(topics);
            args.add(false);
            args.add(Timestamp.valueOf(now));
            args.add(max);
            var ids = jdbc.queryForList(dialect.claimHttpTasksSql(topics.size()), String.class, args.toArray());
            var claimed = new ArrayList<HttpTask>(ids.size());
            for (var id : ids) {
                var token = HttpTaskService.newLeaseToken();
                jdbc.update("UPDATE http_task SET lease_until = ?, worker_id = ?, lease_token = ?, "
                                + "deliveries = deliveries + 1 WHERE id = ?",
                        Timestamp.valueOf(leaseUntil), workerId, token, id);
                find(id).ifPresent(claimed::add);
            }
            claimed.sort(HttpTask.CLAIM_ORDER);
            return claimed;
        });
        return result == null ? List.of() : result;
    }

    @Override
    public Optional<HttpTask> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM http_task WHERE id = ?", ROW, id).stream().findFirst();
    }

    @Override
    public boolean extend(String id, String leaseToken, LocalDateTime until) {
        return jdbc.update("UPDATE http_task SET lease_until = ? WHERE id = ? AND lease_token = ? AND cancelled = ?",
                Timestamp.valueOf(until), id, leaseToken, false) > 0;
    }

    @Override
    public void release(String id, String leaseToken) {
        jdbc.update("UPDATE http_task SET lease_until = NULL WHERE id = ? AND lease_token = ?", id, leaseToken);
    }

    @Override
    public boolean remove(String id, Integer attempt) {
        if (attempt == null) {
            return jdbc.update("DELETE FROM http_task WHERE id = ?", id) > 0;
        }
        return jdbc.update("DELETE FROM http_task WHERE id = ? AND (attempt IS NULL OR attempt = ?)", id, attempt) > 0;
    }

    @Override
    public CancelOutcome cancel(String id, LocalDateTime now) {
        var removed = jdbc.update("DELETE FROM http_task WHERE id = ? AND (lease_until IS NULL OR lease_until < ?)",
                id, Timestamp.valueOf(now));
        if (removed > 0) {
            return CancelOutcome.REMOVED;
        }
        var flagged = jdbc.update("UPDATE http_task SET cancelled = ? WHERE id = ?", true, id);
        return flagged > 0 ? CancelOutcome.FLAGGED : CancelOutcome.ABSENT;
    }

    @Override
    public int purge(LocalDateTime now, LocalDateTime orphansBefore) {
        return jdbc.update("DELETE FROM http_task WHERE (cancelled = ? AND (lease_until IS NULL OR lease_until < ?)) "
                        + "OR created_at < ?",
                true, Timestamp.valueOf(now), Timestamp.valueOf(orphansBefore));
    }

    @Override
    public Counts counts(LocalDateTime now) {
        var at = Timestamp.valueOf(now);
        var leased = jdbc.queryForObject("SELECT COUNT(*) FROM http_task WHERE lease_until >= ?", Long.class, at);
        var available = jdbc.queryForObject("SELECT COUNT(*) FROM http_task WHERE cancelled = ? "
                + "AND (lease_until IS NULL OR lease_until < ?)", Long.class, false, at);
        return new Counts(available == null ? 0 : available, leased == null ? 0 : leased);
    }

    private static final RowMapper<HttpTask> ROW = (rs, row) -> new HttpTask(
            rs.getString("id"),
            rs.getString("process_id"),
            rs.getString("workflow_definition_id"),
            rs.getString("step_id"),
            rs.getString("task_id"),
            rs.getString("topic"),
            read(rs.getString("variables_json")),
            (Integer) rs.getObject("attempt", Integer.class),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("lease_until")),
            rs.getString("worker_id"),
            rs.getString("lease_token"),
            rs.getInt("deliveries"),
            rs.getBoolean("cancelled"),
            rs.getInt("priority"));

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String write(List<Variable> variables) {
        try {
            return JSON.writeValueAsString(variables);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the task's variables", e);
        }
    }

    private static List<Variable> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, VARIABLES);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable variables on a queued HTTP task", e);
        }
    }
}
