package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.ratelimit.RateLimitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Rate-limit windows ({@code rate_limit_window}) and waiting tasks ({@code deferred_dispatch}) in the engine's
 * database (V56), shared by every pod.
 *
 * <p>A permit is one conditional {@code UPDATE}: {@code used = used + 1 WHERE used < permits} in the current
 * window, or a reset to a new window once the old one is over — atomic on any database, and taken in the
 * dispatch's own transaction, so a dispatch that rolls back gives its permit back. A waiting task is claimed with
 * a {@code DELETE} that only one pod's statement can count.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class JdbcRateLimitStore implements RateLimitStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public JdbcRateLimitStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean tryAcquire(String topic, int permits, Duration period, LocalDateTime now) {
        ensureRow(topic);
        var at = Timestamp.valueOf(now);
        var windowOpenedAfter = Timestamp.valueOf(now.minus(period));
        // Twice at most: a pod that lost the race to open the new window finds it open the second time round.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (jdbc.update("UPDATE rate_limit_window SET used = used + 1 WHERE topic = ? AND window_start > ? AND used < ?",
                    topic, windowOpenedAfter, permits) == 1) {
                return true;
            }
            // The window is over: open a new one with this permit (only one pod's update matches the old start).
            if (jdbc.update("UPDATE rate_limit_window SET window_start = ?, used = 1 WHERE topic = ? AND window_start <= ?",
                    at, topic, windowOpenedAfter) == 1) {
                return true;
            }
        }
        return false;
    }

    private void ensureRow(String topic) {
        var exists = jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_window WHERE topic = ?", Long.class, topic);
        if (exists != null && exists > 0) {
            return;
        }
        try {
            // A window that is long over, so the first permit opens a fresh one.
            ownTransaction.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO rate_limit_window (topic, window_start, used) VALUES (?, ?, 0)",
                    topic, Timestamp.valueOf(LocalDateTime.of(1970, 1, 1, 0, 0))));
        } catch (DataIntegrityViolationException raced) {
            // Another pod created it.
        }
    }

    @Override
    public Optional<LocalDateTime> windowEnd(String topic, Duration period) {
        return jdbc.queryForList("SELECT window_start FROM rate_limit_window WHERE topic = ?", Timestamp.class, topic)
                .stream().findFirst().map(start -> start.toLocalDateTime().plus(period));
    }

    @Override
    public void defer(Deferred task) {
        var present = jdbc.queryForObject("SELECT COUNT(*) FROM deferred_dispatch WHERE step_execution_id = ?", Long.class,
                task.stepExecutionId());
        if (present != null && present > 0) {
            return;
        }
        jdbc.update("INSERT INTO deferred_dispatch (step_execution_id, process_id, topic, priority, deferred_at) "
                        + "VALUES (?, ?, ?, ?, ?)", task.stepExecutionId(), task.processId(), task.topic(), task.priority(),
                Timestamp.valueOf(task.deferredAt()));
    }

    @Override
    public boolean hasDeferred(String topic) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM deferred_dispatch WHERE topic = ?", Long.class, topic);
        return count != null && count > 0;
    }

    @Override
    public List<String> deferredTopics() {
        return jdbc.queryForList("SELECT DISTINCT topic FROM deferred_dispatch ORDER BY topic", String.class);
    }

    @Override
    public List<Deferred> deferred(String topic, int max) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT step_execution_id, process_id, topic, priority, deferred_at "
                    + "FROM deferred_dispatch WHERE topic = ? ORDER BY priority DESC, deferred_at, step_execution_id");
            ps.setString(1, topic);
            ps.setMaxRows(max);
            return ps;
        }, (rs, row) -> new Deferred(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getTimestamp(5).toLocalDateTime()));
    }

    @Override
    public boolean claim(String stepExecutionId) {
        return jdbc.update("DELETE FROM deferred_dispatch WHERE step_execution_id = ?", stepExecutionId) == 1;
    }

    @Override
    public long deferredCount() {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM deferred_dispatch", Long.class);
        return count == null ? 0 : count;
    }
}
