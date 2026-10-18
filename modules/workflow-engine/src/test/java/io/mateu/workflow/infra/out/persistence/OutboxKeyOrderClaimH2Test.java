package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-key claim SQL of <b>every</b> dialect, executed — not just read — on H2 in that
 * database's compatibility mode: which rows are heads of their partition key, which are the
 * followers a pass takes with them, and that a row without a key is ordered against nothing.
 *
 * <p>H2 cannot show the concurrency (it locks every row a {@code FOR UPDATE} query matches), so
 * the two-claimer behaviour is in {@link PostgresDbLockDialectPostgresTest}; this pins the
 * predicate itself for the dialects there is no container for here, MariaDB and Oracle included.
 */
class OutboxKeyOrderClaimH2Test {

    private static final LocalDateTime T0 = LocalDateTime.now().minusMinutes(10);

    private Connection connection;
    private JdbcTemplate jdbc;

    static Stream<Arguments> dialects() {
        return Stream.of(
                Arguments.of("H2", new H2DbLockDialect(), ""),
                Arguments.of("PostgreSQL", new PostgresDbLockDialect(), ";MODE=PostgreSQL"),
                Arguments.of("MariaDB", new MariaDbLockDialect(), ";MODE=MariaDB"),
                Arguments.of("Oracle", new OracleDbLockDialect(), ";MODE=Oracle"));
    }

    private void open(String mode) throws Exception {
        connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:key-order-" + UUID.randomUUID() + mode, "sa", "");
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        // The columns the claim reads, as V1 and V33 create them.
        jdbc.execute("""
                CREATE TABLE outbox_message_entity (
                    id            VARCHAR(255) PRIMARY KEY,
                    timestamp     TIMESTAMP,
                    status        VARCHAR(64),
                    message_type  VARCHAR(512),
                    payload       TEXT,
                    partition_key VARCHAR(255)
                )""");
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    private void row(String id, String key, String status, int secondsAfterT0) {
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload, partition_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, Timestamp.valueOf(T0.plusSeconds(secondsAfterT0)), status, "io.mateu.Whatever", "{}", key);
    }

    private List<String> heads(DbLockDialect dialect, int limit) {
        return jdbc.query(dialect.claimPendingOutboxSql(), ps -> ps.setInt(1, limit), (rs, i) -> rs.getString(1));
    }

    private List<String> followers(DbLockDialect dialect, int limit, String... keys) {
        var ids = new ArrayList<String>(jdbc.query(dialect.claimFollowingOutboxSql(keys.length), ps -> {
            for (int i = 0; i < keys.length; i++) {
                ps.setString(i + 1, keys[i]);
            }
            ps.setInt(keys.length + 1, limit);
        }, (rs, i) -> rs.getString(1)));
        return ids;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void onlyTheOldestPendingRowOfEachKeyIsAHead(String name, DbLockDialect dialect, String mode) throws Exception {
        open(mode);
        row("p1", "P", "Pending", 1);
        row("p2", "P", "Pending", 2);
        row("q1", "Q", "Pending", 3);
        row("p3", "P", "Pending", 4);
        row("q2", "Q", "Pending", 5);

        assertThat(heads(dialect, 10)).containsExactlyInAnyOrder("p1", "q1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void sentAndErrorRowsDoNotHoldTheirSuccessorsBack(String name, DbLockDialect dialect, String mode) throws Exception {
        open(mode);
        row("p1", "P", "Sent", 1);
        row("p2", "P", "Error", 2);
        row("p3", "P", "Pending", 3);
        row("p4", "P", "Pending", 4);

        assertThat(heads(dialect, 10)).containsExactly("p3");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aRowWithoutAKeyIsOrderedAgainstNothing(String name, DbLockDialect dialect, String mode) throws Exception {
        open(mode);
        // Unkeyed events, and rows written before V33 added the column: claimed as they always were.
        row("u1", null, "Pending", 1);
        row("u2", null, "Pending", 2);
        row("p1", "P", "Pending", 3);

        assertThat(heads(dialect, 10)).containsExactlyInAnyOrder("u1", "u2", "p1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aTimestampTieIsBrokenByIdSoAKeyHasOneHead(String name, DbLockDialect dialect, String mode) throws Exception {
        open(mode);
        row("b", "P", "Pending", 1);
        row("a", "P", "Pending", 1);

        // The drain sorts a batch by (timestamp, id) as well, so both agree on which goes first.
        assertThat(heads(dialect, 10)).containsExactly("a");
        assertThat(followers(dialect, 10, "P")).containsExactly("a", "b");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void theHeadLimitCountsHeadsOnly(String name, DbLockDialect dialect, String mode) throws Exception {
        open(mode);
        row("p1", "P", "Pending", 1);
        row("p2", "P", "Pending", 2);
        row("q1", "Q", "Pending", 3);
        row("r1", "R", "Pending", 4);

        assertThat(heads(dialect, 2)).containsExactlyInAnyOrder("p1", "q1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void theRunsOfTheGivenKeysComeBackOldestFirstAndAPrefixOfEach(String name, DbLockDialect dialect, String mode)
            throws Exception {
        open(mode);
        row("p1", "P", "Pending", 1);
        row("q1", "Q", "Pending", 2);
        row("p2", "P", "Pending", 3);
        row("q2", "Q", "Pending", 4);
        row("p3", "P", "Pending", 5);
        row("q3", "Q", "Pending", 6);
        row("r1", "R", "Pending", 7);
        row("r2", "R", "Pending", 8);
        row("u1", null, "Pending", 9);

        // Heads included (the drain drops the ones it holds), never another key's row or an unkeyed one.
        assertThat(followers(dialect, 10, "P", "Q")).containsExactlyInAnyOrder("p1", "q1", "p2", "q2", "p3", "q3");
        // Under a limit, the oldest first — so what each key gets is a prefix of its run, never a
        // later row without the one before it.
        assertThat(followers(dialect, 3, "P", "Q")).containsExactlyInAnyOrder("p1", "q1", "p2");
        assertThat(followers(dialect, 2, "R")).containsExactlyInAnyOrder("r1", "r2");
    }
}
