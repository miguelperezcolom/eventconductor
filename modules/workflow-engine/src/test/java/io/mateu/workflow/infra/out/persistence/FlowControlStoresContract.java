package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.flowcontrol.FlowControlStore;
import io.mateu.workflow.application.ratelimit.RateLimitStore;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V56 tables' JDBC stores — flow control, worker rate limits, business-key holders — over the real
 * migrations (V56 and the V56.1 Java migration that drops the UNIQUE on process_entity.business_key).
 */
abstract class FlowControlStoresContract {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 19, 10, 0);

    DataSource dataSource;
    JdbcTemplate jdbc;
    JdbcFlowControlStore flow;
    JdbcRateLimitStore limits;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void open() {
        dataSource = newMigratedDatabase();
        jdbc = new JdbcTemplate(dataSource);
        var tx = new DataSourceTransactionManager(dataSource);
        flow = new JdbcFlowControlStore(jdbc, tx);
        limits = new JdbcRateLimitStore(jdbc, tx);
        EngineClock.set(java.time.Clock.fixed(T0.toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        EngineClock.reset();
        dispose(dataSource);
    }

    @Test
    void aKeysStateAndItsWaitingStartsAreKeptUnderTheKey() {
        var created = flow.withKey("k1", "start:wf", "a", FlowControlStore.KeyTx::state);
        assertThat(created.windowCount()).isZero();
        assertThat(created.scope()).isEqualTo("start:wf");
        flow.withKey("k1", "start:wf", "a", tx -> {
            tx.saveState(tx.state().withWindow(T0, 2).withLastStart("p-1", "bk-1", T0));
            tx.savePending(new FlowControlStore.Pending("k1", "k1", FlowControlStore.Kind.DEBOUNCE, "wf", "{}",
                    T0.plusSeconds(30), T0, 1));
            tx.savePending(new FlowControlStore.Pending("k1-002", "k1", FlowControlStore.Kind.THROTTLE, "wf", "{\"n\":2}",
                    T0.plusMinutes(1), T0, 1));
            tx.savePending(new FlowControlStore.Pending("k1-001", "k1", FlowControlStore.Kind.THROTTLE, "wf", "{\"n\":1}",
                    T0.plusMinutes(1), T0.plusSeconds(1), 1));
            return null;
        });
        flow.withKey("k1", null, null, tx -> {
            assertThat(tx.state().windowCount()).isEqualTo(2);
            assertThat(tx.state().lastProcessId()).isEqualTo("p-1");
            assertThat(tx.state().lastStartedAt()).isEqualTo(T0);
            assertThat(tx.debounce()).hasValueSatisfying(p -> assertThat(p.dueAt()).isEqualTo(T0.plusSeconds(30)));
            assertThat(tx.queued()).extracting(FlowControlStore.Pending::id).containsExactly("k1-001", "k1-002");
            // An upsert replaces the data and the due moment.
            tx.savePending(tx.debounce().orElseThrow().withDueAt(T0.plusSeconds(45)));
            return null;
        });
        assertThat(flow.pending(FlowControlStore.Kind.THROTTLE)).isEqualTo(2);
        assertThat(flow.pending(FlowControlStore.Kind.DEBOUNCE)).isEqualTo(1);
        assertThat(flow.nextDueAt()).contains(T0.plusSeconds(45));
        assertThat(flow.dueKeys(T0.plusSeconds(10), 10)).isEmpty();
        assertThat(flow.dueKeys(T0.plusSeconds(45), 10)).containsExactly("k1");
        flow.withKey("k1", null, null, tx -> {
            tx.queued().forEach(p -> tx.deletePending(p.id()));
            tx.deletePending("k1");
            return null;
        });
        assertThat(flow.nextDueAt()).isEmpty();
        assertThatThrownBy(() -> flow.withKey("unknown", null, null, FlowControlStore.KeyTx::state))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ratePermitsAreAFixedWindowAndWaitingTasksAreClaimedOnce() {
        var period = Duration.ofMinutes(1);
        assertThat(limits.tryAcquire("partner", 2, period, T0)).isTrue();
        assertThat(limits.tryAcquire("partner", 2, period, T0.plusSeconds(10))).isTrue();
        assertThat(limits.tryAcquire("partner", 2, period, T0.plusSeconds(20))).isFalse();
        assertThat(limits.windowEnd("partner", period)).contains(T0.plusMinutes(1));
        assertThat(limits.tryAcquire("partner", 2, period, T0.plusMinutes(1))).isTrue();
        assertThat(limits.windowEnd("partner", period)).contains(T0.plusMinutes(2));
        assertThat(limits.windowEnd("nothing", period)).isEmpty();

        limits.defer(new RateLimitStore.Deferred("s1", "p1", "partner", 50, T0));
        limits.defer(new RateLimitStore.Deferred("s1", "p1", "partner", 50, T0));
        limits.defer(new RateLimitStore.Deferred("s2", "p2", "partner", 90, T0.plusSeconds(5)));
        limits.defer(new RateLimitStore.Deferred("s3", "p3", "other", 50, T0));
        assertThat(limits.deferredCount()).isEqualTo(3);
        assertThat(limits.hasDeferred("partner")).isTrue();
        assertThat(limits.hasDeferred("nope")).isFalse();
        assertThat(limits.deferredTopics()).containsExactly("other", "partner");
        assertThat(limits.deferred("partner", 10)).extracting(RateLimitStore.Deferred::stepExecutionId)
                .containsExactly("s2", "s1");
        assertThat(limits.claim("s2")).isTrue();
        assertThat(limits.claim("s2")).isFalse();
    }

    @Test
    void theBusinessKeyStaysUniqueAndARunHandsItOverByLettingGoOfIt() {
        insertProcess("run-1", "order-1");
        // The V1 constraint is still there: an older engine's duplicate creation still fails on it.
        assertThatThrownBy(() -> insertProcess("run-x", "order-1")).isInstanceOf(DataIntegrityViolationException.class);

        // What a newer engine does to hand the key over (ProcessDBRepository#releaseBusinessKey, then the insert).
        assertThat(release("run-1", "order-1")).isEqualTo(1);
        insertProcess("run-2", "order-1");
        assertThat(jdbc.queryForObject("SELECT id FROM process_entity WHERE business_key = 'order-1'", String.class))
                .isEqualTo("run-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity WHERE business_key = 'order-1'"
                + " OR released_business_key = 'order-1'", Integer.class)).isEqualTo(2);
        // A run that let go of its key cannot let go of it twice: the second takeover finds nothing to take.
        assertThat(release("run-1", "order-1")).isZero();
        // V56.1's indexes are there.
        assertThat(indexesOf("process_entity")).contains("idx_process_released_business_key");
        assertThat(indexesOf("http_task")).contains("idx_http_task_topic_priority");
    }

    java.util.Set<String> indexesOf(String table) {
        try (var connection = dataSource.getConnection()) {
            var names = new java.util.HashSet<String>();
            for (var name : new String[]{table, table.toUpperCase()}) {
                try (var rows = connection.getMetaData().getIndexInfo(null, null, name, false, false)) {
                    while (rows.next()) {
                        var index = rows.getString("INDEX_NAME");
                        if (index != null) {
                            names.add(index.toLowerCase());
                        }
                    }
                }
            }
            return names;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    int release(String id, String businessKey) {
        return jdbc.update("UPDATE process_entity SET business_key = NULL, released_business_key = ? "
                + "WHERE id = ? AND business_key = ?", businessKey, id, businessKey);
    }

    void insertProcess(String id, String businessKey) {
        jdbc.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, created) VALUES (?, ?, ?, ?, ?)",
                id, businessKey, "RUNNING", "wf", Timestamp.valueOf(T0));
    }
}
