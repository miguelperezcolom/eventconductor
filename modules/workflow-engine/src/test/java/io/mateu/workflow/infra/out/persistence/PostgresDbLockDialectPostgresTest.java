package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.mateu.workflow.infra.out.async.OutboxKeyOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

    // ─────────────────────────── per-key order across pods ───────────────────────────

    private void keyed(String id, String key, String status, LocalDateTime timestamp) {
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload, partition_key) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, Timestamp.valueOf(timestamp), status, "io.mateu.Whatever", "{}", key);
    }

    /**
     * One pass's selection, the way {@code OutboxDrain} makes it: the dialect's claim, then
     * {@link OutboxKeyOrder}'s read of the claimed keys' pending rows and its verdict. Returns the
     * claimed ids that may be sent now, oldest first.
     */
    private List<String> sendable(Connection con, int limit) throws Exception {
        var claimed = rows(con, claim(con, limit));
        var keys = claimed.stream().map(OutboxKeyOrder.Row::key).filter(k -> k != null).distinct().toList();
        var pending = new ArrayList<OutboxKeyOrder.Row>();
        if (!keys.isEmpty()) {
            var newest = claimed.stream().filter(r -> r.key() != null).map(OutboxKeyOrder.Row::timestamp)
                    .max(Comparator.naturalOrder()).orElseThrow();
            try (var ps = con.prepareStatement(OutboxKeyOrder.pendingOfKeysSql(keys.size()))) {
                for (int i = 0; i < keys.size(); i++) {
                    ps.setString(i + 1, keys.get(i));
                }
                ps.setTimestamp(keys.size() + 1, Timestamp.valueOf(newest));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pending.add(new OutboxKeyOrder.Row(rs.getString(1), rs.getString(2),
                                rs.getTimestamp(3).toLocalDateTime()));
                    }
                }
            }
        }
        var ok = OutboxKeyOrder.sendable(claimed, pending);
        return claimed.stream()
                .filter(r -> ok.contains(r.id()))
                .sorted(Comparator.comparing(OutboxKeyOrder.Row::timestamp).thenComparing(OutboxKeyOrder.Row::id))
                .map(OutboxKeyOrder.Row::id)
                .toList();
    }

    private static void lockTimeout(Connection con) throws Exception {
        try (var st = con.createStatement()) {
            st.execute("SET lock_timeout = '2s'");
        }
    }

    @Test
    void whileOnePodHoldsAKeysOldestRowNoOtherPodSendsANewerOne() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        keyed("p1", "P", "Pending", t0.plusSeconds(1));
        keyed("p2", "P", "Pending", t0.plusSeconds(2));
        keyed("q1", "Q", "Pending", t0.plusSeconds(3));
        keyed("p3", "P", "Pending", t0.plusSeconds(4));
        keyed("q2", "Q", "Pending", t0.plusSeconds(5));
        keyed("u1", null, "Pending", t0.plusSeconds(6));

        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            lockTimeout(podA);
            lockTimeout(podB);

            // A's limit cuts between p1 and p2 — one of the ways the plain claim split a process.
            assertThat(sendable(podA, 1)).containsExactly("p1");
            // B's SKIP LOCKED claim takes p2 and p3 too, as it always did — but while p1 is Pending
            // and not B's, they are not B's to send. Q and the unkeyed row are.
            assertThat(claim(podB, 10)).contains("p2", "p3");
            podB.rollback();
            assertThat(sendable(podB, 10)).containsExactly("q1", "q2", "u1");

            podA.rollback();
            podB.rollback();
        }
    }

    @Test
    void aHeadWhoseSendFailedHoldsItsSuccessorsBackOnEveryPod() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        keyed("p1", "P", "Pending", t0.plusSeconds(1));
        keyed("p2", "P", "Pending", t0.plusSeconds(2));
        keyed("p3", "P", "Pending", t0.plusSeconds(3));

        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            // A claims p1, its send fails, the pass commits with p1 still Pending.
            assertThat(sendable(podA, 1)).containsExactly("p1");
            podA.commit();

            // B, the next pod to look, gets the whole run back and may send it in order from p1.
            assertThat(sendable(podB, 10)).containsExactly("p1", "p2", "p3");
            podB.rollback();

            // But if A holds p1 again (retrying), B may send nothing of P at all.
            assertThat(sendable(podA, 1)).containsExactly("p1");
            assertThat(sendable(podB, 10)).isEmpty();
            podA.rollback();
            podB.rollback();
        }
    }

    @Test
    void onceTheOlderRowIsSentItsSuccessorsMayGo() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        keyed("p1", "P", "Pending", t0.plusSeconds(1));
        keyed("p2", "P", "Pending", t0.plusSeconds(2));

        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            assertThat(sendable(podA, 1)).containsExactly("p1");
            try (var ps = podA.prepareStatement("UPDATE outbox_message_entity SET status = 'Sent' WHERE id = 'p1'")) {
                ps.executeUpdate();
            }
            // Sent but not committed: still Pending to everyone else, so p2 waits.
            assertThat(sendable(podB, 10)).isEmpty();
            podB.rollback();
            podA.commit();
            assertThat(sendable(podB, 10)).containsExactly("p2");
            podB.rollback();
        }
    }

    @Test
    void aTimestampTieIsOrderedByIdTheWayTheDrainSortsABatch() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        keyed("b", "P", "Pending", t0);
        keyed("a", "P", "Pending", t0);
        try (var podA = dataSource.getConnection(); var podB = dataSource.getConnection()) {
            podA.setAutoCommit(false);
            podB.setAutoCommit(false);
            var claimedByA = claim(podA, 1).getFirst();
            // Whichever of the two A happened to lock, B may send "b" only if A holds neither.
            var byB = sendable(podB, 10);
            if (claimedByA.equals("a")) {
                assertThat(byB).isEmpty();
            } else {
                assertThat(byB).as("A holds b; a is B's and first").containsExactly("a");
            }
            podA.rollback();
            podB.rollback();
        }
    }

    /**
     * The end-to-end property, under real concurrency: several relays drain one outbox, each pass
     * claims, keeps what {@link OutboxKeyOrder} allows, "publishes" each key's rows in order with
     * random sends that fail (left Pending, the rest of the key held back) and random pauses while
     * the locks are held. Whatever the interleaving, every key must come out exactly in the order it
     * was written. Against the plain claim alone this fails.
     */
    @Test
    void concurrentRelaysPublishEveryKeysEventsInTheOrderTheyWereWritten() throws Exception {
        var t0 = LocalDateTime.now().minusMinutes(10);
        int keys = 24;
        int perKey = 12;
        int n = 0;
        for (int seq = 0; seq < perKey; seq++) {
            for (int k = 0; k < keys; k++) {
                // Interleaved in time across keys, so every batch mixes processes.
                keyed(String.format("k%02d-%02d", k, seq), "key-" + k, "Pending", t0.plusNanos(1_000L * n++));
            }
        }

        Map<String, List<String>> published = new ConcurrentHashMap<>();
        int relays = 4;
        var pool = Executors.newFixedThreadPool(relays);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int r = 0; r < relays; r++) {
            futures.add(pool.submit(() -> {
                try (var con = dataSource.getConnection()) {
                    con.setAutoCommit(false);
                    try (var st = con.createStatement()) {
                        st.execute("SET lock_timeout = '10s'");
                    }
                    var random = ThreadLocalRandom.current();
                    while (System.nanoTime() < deadline && pendingCount() > 0) {
                        relayOnePass(con, 1 + random.nextInt(8), published);
                    }
                }
                return null;
            }));
        }
        for (var future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(pendingCount()).as("everything was relayed").isZero();
        assertThat(published).hasSize(keys);
        published.forEach((key, ids) -> assertThat(ids)
                .as("the order %s reached the broker in", key)
                .hasSize(perKey)
                .isSortedAccordingTo(Comparator.naturalOrder()));
    }

    private int pendingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_message_entity WHERE status = 'Pending'", Integer.class);
    }

    /** One relay pass the way OutboxDrain makes it: claim, hold back, each key in order, stop at a failure. */
    private void relayOnePass(Connection con, int batchSize, Map<String, List<String>> published) throws Exception {
        var random = ThreadLocalRandom.current();
        var ids = sendable(con, batchSize);
        if (ids.isEmpty()) {
            con.rollback();
            Thread.sleep(1);
            return;
        }
        var byKey = new LinkedHashMap<String, List<String>>();
        rows(con, ids).stream()
                .sorted(Comparator.comparing(OutboxKeyOrder.Row::timestamp).thenComparing(OutboxKeyOrder.Row::id))
                .forEach(row -> byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row.id()));

        var sent = new ArrayList<String>();
        for (var entry : byKey.entrySet()) {
            for (var id : entry.getValue()) {
                if (random.nextInt(10) == 0) {
                    break; // refused: this row stays Pending and holds the rest of its key back
                }
                published.computeIfAbsent(entry.getKey(), k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                        .add(id);
                sent.add(id);
                if (random.nextBoolean()) {
                    Thread.sleep(random.nextInt(3)); // widen the window other relays race in
                }
            }
        }
        if (!sent.isEmpty()) {
            try (var ps = con.prepareStatement("UPDATE outbox_message_entity SET status = 'Sent' WHERE id = ANY (?)")) {
                ps.setArray(1, con.createArrayOf("varchar", sent.toArray()));
                ps.executeUpdate();
            }
        }
        con.commit();
    }

    private List<OutboxKeyOrder.Row> rows(Connection con, List<String> ids) throws Exception {
        var rows = new ArrayList<OutboxKeyOrder.Row>();
        if (ids.isEmpty()) {
            return rows;
        }
        try (var ps = con.prepareStatement(
                "SELECT id, partition_key, timestamp FROM outbox_message_entity WHERE id = ANY (?)")) {
            ps.setArray(1, con.createArrayOf("varchar", ids.toArray()));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new OutboxKeyOrder.Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toLocalDateTime()));
                }
            }
        }
        return rows;
    }
}
