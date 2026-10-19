package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.flowcontrol.FlowControlStore;
import io.mateu.workflow.time.EngineClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Flow-control state in the engine's database ({@code flow_control_key}, {@code flow_control_pending}, V56),
 * shared by every pod.
 *
 * <p>{@link #withKey} runs in a transaction (the caller's, when there is one) that opens with
 * {@code SELECT … FOR UPDATE} on the key's row: two pods deciding for the same key take turns, and whatever
 * the first decided — a window count, a pending debounce, a queued start — is what the second reads. The row is
 * created beforehand in a transaction of its own (a concurrent creation of the same row is simply the other pod
 * winning), so the locking transaction never runs a statement that can fail and poison it.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class JdbcFlowControlStore implements FlowControlStore {

    private static final String PENDING_COLUMNS =
            "id, key_id, kind, workflow_id, request_json, due_at, enqueued_at, absorbed";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate ownTransaction;

    public JdbcFlowControlStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T withKey(String keyId, String scope, String flowKey, Function<KeyTx, T> action) {
        if (scope != null) {
            ensureRow(keyId, scope, flowKey);
        }
        return transactions.execute(status -> {
            var rows = jdbc.query("SELECT id, scope, flow_key, window_start, window_count, last_process_id, "
                    + "last_business_key, last_started_at FROM flow_control_key WHERE id = ? FOR UPDATE", STATE, keyId);
            if (rows.isEmpty()) {
                throw new IllegalStateException("No flow-control state for key " + keyId);
            }
            var tx = new JdbcKeyTx(rows.getFirst());
            return action.apply(tx);
        });
    }

    private void ensureRow(String keyId, String scope, String flowKey) {
        var exists = jdbc.queryForObject("SELECT COUNT(*) FROM flow_control_key WHERE id = ?", Long.class, keyId);
        if (exists != null && exists > 0) {
            return;
        }
        try {
            ownTransaction.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO flow_control_key (id, scope, flow_key, window_count, updated_at) VALUES (?, ?, ?, 0, ?)",
                    keyId, cut(scope, 512), cut(flowKey, 1024), Timestamp.valueOf(EngineClock.now())));
        } catch (DataIntegrityViolationException raced) {
            // Another pod created it first: it exists, which is all this wanted.
        }
    }

    private final class JdbcKeyTx implements KeyTx {
        private KeyState state;

        JdbcKeyTx(KeyState state) {
            this.state = state;
        }

        @Override
        public KeyState state() {
            return state;
        }

        @Override
        public void saveState(KeyState newState) {
            jdbc.update("UPDATE flow_control_key SET window_start = ?, window_count = ?, last_process_id = ?, "
                            + "last_business_key = ?, last_started_at = ?, updated_at = ? WHERE id = ?",
                    ts(newState.windowStart()), newState.windowCount(), newState.lastProcessId(),
                    cut(newState.lastBusinessKey(), 255), ts(newState.lastStartedAt()), ts(EngineClock.now()),
                    newState.keyId());
            this.state = newState;
        }

        @Override
        public Optional<Pending> debounce() {
            return jdbc.query("SELECT " + PENDING_COLUMNS + " FROM flow_control_pending WHERE key_id = ? AND kind = ?",
                    PENDING, state.keyId(), Kind.DEBOUNCE.name()).stream().findFirst();
        }

        @Override
        public List<Pending> queued() {
            return jdbc.query("SELECT " + PENDING_COLUMNS + " FROM flow_control_pending WHERE key_id = ? AND kind = ?"
                    + " ORDER BY id", PENDING, state.keyId(), Kind.THROTTLE.name());
        }

        @Override
        public void savePending(Pending p) {
            var updated = jdbc.update("UPDATE flow_control_pending SET request_json = ?, due_at = ?, absorbed = ? WHERE id = ?",
                    p.requestJson(), ts(p.dueAt()), p.absorbed(), p.id());
            if (updated == 0) {
                jdbc.update("INSERT INTO flow_control_pending (" + PENDING_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        p.id(), p.keyId(), p.kind().name(), p.workflowId(), p.requestJson(), ts(p.dueAt()),
                        ts(p.enqueuedAt()), p.absorbed());
            }
        }

        @Override
        public void deletePending(String id) {
            jdbc.update("DELETE FROM flow_control_pending WHERE id = ?", id);
        }
    }

    @Override
    public List<String> dueKeys(LocalDateTime now, int max) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT key_id, MIN(due_at) AS due FROM flow_control_pending WHERE due_at <= ?"
                    + " GROUP BY key_id ORDER BY due");
            ps.setTimestamp(1, Timestamp.valueOf(now));
            ps.setMaxRows(max);
            return ps;
        }, (rs, row) -> rs.getString(1));
    }

    @Override
    public Optional<LocalDateTime> nextDueAt() {
        var next = jdbc.queryForObject("SELECT MIN(due_at) FROM flow_control_pending", Timestamp.class);
        return Optional.ofNullable(next).map(Timestamp::toLocalDateTime);
    }

    @Override
    public long pending(Kind kind) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM flow_control_pending WHERE kind = ?", Long.class, kind.name());
        return count == null ? 0 : count;
    }

    private static final RowMapper<KeyState> STATE = (rs, row) -> new KeyState(rs.getString("id"), rs.getString("scope"),
            rs.getString("flow_key"), local(rs.getTimestamp("window_start")), rs.getInt("window_count"),
            rs.getString("last_process_id"), rs.getString("last_business_key"), local(rs.getTimestamp("last_started_at")));

    private static final RowMapper<Pending> PENDING = (rs, row) -> new Pending(rs.getString("id"), rs.getString("key_id"),
            Kind.valueOf(rs.getString("kind")), rs.getString("workflow_id"), rs.getString("request_json"),
            local(rs.getTimestamp("due_at")), local(rs.getTimestamp("enqueued_at")), rs.getInt("absorbed"));

    private static LocalDateTime local(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Timestamp ts(LocalDateTime time) {
        return time == null ? null : Timestamp.valueOf(time);
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
