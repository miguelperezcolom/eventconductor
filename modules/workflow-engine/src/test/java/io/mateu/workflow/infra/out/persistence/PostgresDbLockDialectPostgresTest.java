package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link PostgresDbLockDialect} against a real PostgreSQL: the advisory locks every scheduler's
 * leader election rests on, the relay gate (shared by every relay, exclusive for the chaos tests),
 * and the outbox claim whose {@code FOR UPDATE SKIP LOCKED} is what lets several pods drain one
 * outbox without electing anyone. None of it can be exercised on H2, whose dialect is a JVM map and
 * whose claim SQL is different. Skipped without Docker.
 */
class PostgresDbLockDialectPostgresTest {

    private static HikariDataSource dataSource;
    private final PostgresDbLockDialect dialect = new PostgresDbLockDialect();
    private JdbcTemplate jdbc;

    @BeforeAll
    static void database() {
        dataSource = PostgresTestDatabase.freshMigratedDatabase();
    }

    @AfterAll
    static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox_message_entity");
    }

    // ─────────────────────────── advisory locks ───────────────────────────

    @Test
    void anAdvisoryLockExcludesOtherSessionsUntilReleased() throws Exception {
        try (var a = dataSource.getConnection(); var b = dataSource.getConnection()) {
            assertThat(dialect.tryLock(a, 9001L)).isTrue();
            assertThat(dialect.tryLock(b, 9001L)).as("held by another session").isFalse();
            assertThat(dialect.tryLock(b, 9002L)).as("a different id is a different lock").isTrue();

            dialect.unlock(a, 9001L);
            assertThat(dialect.tryLock(b, 9001L)).isTrue();

            dialect.unlock(b, 9001L);
            dialect.unlock(b, 9002L);
        }
    }

    @Test
    void theLockIsTheSessionsSoItSurvivesAClosedTransactionButNotTheSession() throws Exception {
        try (var b = dataSource.getConnection()) {
            // A physical connection, not a pooled one: closing it has to end the session.
            try (var a = java.sql.DriverManager.getConnection(
                    dataSource.getJdbcUrl(), dataSource.getUsername(), dataSource.getPassword())) {
                a.setAutoCommit(false);
                assertThat(dialect.tryLock(a, 9003L)).isTrue();
                a.commit();
                assertThat(dialect.tryLock(b, 9003L)).as("still held after commit").isFalse();
            }
            // The server ends the session as the socket closes — a moment later, not synchronously.
            await().atMost(Duration.ofSeconds(5)).until(() -> dialect.tryLock(b, 9003L));
            dialect.unlock(b, 9003L);
        }
    }

    @Test
    void theRelayGateIsSharedByRelaysAndExclusiveAgainstAFreeze() throws Exception {
        try (var relay1 = dataSource.getConnection();
             var relay2 = dataSource.getConnection();
             var chaos = dataSource.getConnection()) {
            // Two relays drain at the same time: the gate never makes them wait on each other.
            assertThat(dialect.tryRelayGate(relay1)).isTrue();
            assertThat(dialect.tryRelayGate(relay2)).isTrue();
            // A test freezing every relay takes the gate's id exclusively — not while one drains.
            assertThat(dialect.tryLock(chaos, DIST_RELAY_GATE_ID)).isFalse();

            dialect.releaseRelayGate(relay1);
            dialect.releaseRelayGate(relay2);
            assertThat(dialect.tryLock(chaos, DIST_RELAY_GATE_ID)).isTrue();
            // Frozen: no relay passes the gate until the freeze lifts.
            assertThat(dialect.tryRelayGate(relay1)).isFalse();

            dialect.unlock(chaos, DIST_RELAY_GATE_ID);
            assertThat(dialect.tryRelayGate(relay1)).isTrue();
            dialect.releaseRelayGate(relay1);
        }
    }

    /** The id workflow-dist-e2e's chaos tests lock exclusively to freeze every relay at once. */
    private static final long DIST_RELAY_GATE_ID = 111222333L;

    @Test
    void noSchedulerLockSharesTheRelayGatesId() {
        // On the gate's id a leader lock would starve whenever any relay drained, and freeze every
        // relay while it was held.
        assertThat(AnalyticsRollupProjector.LOCK_ID).isNotEqualTo(DIST_RELAY_GATE_ID);
    }

    // ─────────────────────────── outbox claims ───────────────────────────

    private void outbox(String id, String status, LocalDateTime timestamp) {
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload) "
                + "VALUES (?, ?, ?, ?, ?)", id, Timestamp.valueOf(timestamp), status, "io.mateu.Whatever", "{}");
    }

    private List<String> claim(Connection con, int limit) throws Exception {
        try (var ps = con.prepareStatement(dialect.claimPendingOutboxSql())) {
            ps.setInt(1, limit);
            try (var rs = ps.executeQuery()) {
                var ids = new ArrayList<String>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }

    @Test
    void aClaimTakesTheOldestPendingRowsUpToItsLimit() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        outbox("m3", "Pending", t0.plusSeconds(3));
        outbox("m1", "Pending", t0.plusSeconds(1));
        outbox("sent", "Sent", t0);
        outbox("error", "Error", t0);
        outbox("m2", "Pending", t0.plusSeconds(2));

        try (var con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            assertThat(claim(con, 2)).containsExactly("m1", "m2");
            con.rollback();
        }
    }

    @Test
    void concurrentClaimsSkipEachOthersRowsInsteadOfWaiting() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        for (int i = 1; i <= 5; i++) {
            outbox("m" + i, "Pending", t0.plusSeconds(i));
        }
        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            // A bounded wait, so a claim that blocked on the other pod's locks fails the test fast
            // rather than hanging it.
            try (var st = podB.createStatement()) {
                st.execute("SET lock_timeout = '2s'");
            }

            var a = claim(podA, 2);
            var b = claim(podB, 10);

            assertThat(a).containsExactly("m1", "m2");
            // SKIP LOCKED: B neither waits for A nor gets A's rows — it drains the rest.
            assertThat(b).containsExactly("m3", "m4", "m5");

            podA.rollback();
            podB.rollback();
        }
        // Released with the transactions: the rows are claimable again.
        try (var con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            assertThat(claim(con, 10)).containsExactly("m1", "m2", "m3", "m4", "m5");
            con.rollback();
        }
    }

    @Test
    void theLimitIsInsideTheStatementSoOnlyTheClaimedRowsAreLocked() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        for (int i = 1; i <= 4; i++) {
            outbox("m" + i, "Pending", t0.plusSeconds(i));
        }
        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            assertThat(claim(podA, 1)).containsExactly("m1");
            // Had A locked every matching row (a client-side limit), B would find nothing.
            assertThat(claim(podB, 1)).containsExactly("m2");
            podA.rollback();
            podB.rollback();
        }
    }

    @Test
    void retentionSelectsOnlySentRowsOlderThanTheCutoff() throws Exception {
        var now = LocalDateTime.now();
        outbox("old-sent-1", "Sent", now.minusHours(5));
        outbox("old-sent-2", "Sent", now.minusHours(4));
        outbox("old-pending", "Pending", now.minusHours(5));
        outbox("old-error", "Error", now.minusHours(5));
        outbox("new-sent", "Sent", now.minusMinutes(5));

        try (var con = dataSource.getConnection();
             var ps = con.prepareStatement(dialect.selectSentOutboxToPurgeSql())) {
            ps.setTimestamp(1, Timestamp.valueOf(now.minusHours(1)));
            ps.setInt(2, 10);
            var ids = new ArrayList<String>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            assertThat(ids).containsExactly("old-sent-1", "old-sent-2");
        }
    }
}
