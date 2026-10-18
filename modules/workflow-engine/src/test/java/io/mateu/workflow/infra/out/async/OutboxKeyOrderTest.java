package io.mateu.workflow.infra.out.async;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The verdict that keeps a process's events in order across relaying pods: of the rows a pass
 * claimed, only those forming an unbroken prefix of their key's pending run may be sent.
 */
class OutboxKeyOrderTest {

    private static final LocalDateTime T0 = LocalDateTime.now().minusMinutes(5);

    private static OutboxKeyOrder.Row row(String id, String key, int seconds) {
        return new OutboxKeyOrder.Row(id, key, T0.plusSeconds(seconds));
    }

    @Test
    void aClaimedRunThatStartsAtTheKeysOldestPendingRowIsSendable() {
        var p1 = row("p1", "P", 1);
        var p2 = row("p2", "P", 2);
        assertThat(OutboxKeyOrder.sendable(List.of(p2, p1), List.of(p2, p1))).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void anOlderPendingRowThisPassDoesNotHoldStopsTheKey() {
        var p1 = row("p1", "P", 1);
        var p2 = row("p2", "P", 2);
        var p3 = row("p3", "P", 3);
        var q1 = row("q1", "Q", 4);
        // Another pod holds p1 (or its send failed): p2 and p3 must wait, Q is unaffected.
        assertThat(OutboxKeyOrder.sendable(List.of(p2, p3, q1), List.of(p1, p2, p3, q1)))
                .containsExactly("q1");
    }

    @Test
    void aGapInTheMiddleKeepsOnlyThePrefix() {
        var p1 = row("p1", "P", 1);
        var p2 = row("p2", "P", 2);
        var p3 = row("p3", "P", 3);
        assertThat(OutboxKeyOrder.sendable(List.of(p1, p3), List.of(p1, p2, p3))).containsExactly("p1");
    }

    @Test
    void aRowWithoutAKeyIsOrderedAgainstNothing() {
        var u = row("u", null, 1);
        assertThat(OutboxKeyOrder.sendable(List.of(u), List.of())).containsExactly("u");
    }

    @Test
    void timestampTiesAreOrderedById() {
        var a = new OutboxKeyOrder.Row("a", "P", T0);
        var b = new OutboxKeyOrder.Row("b", "P", T0);
        assertThat(OutboxKeyOrder.sendable(List.of(b), List.of(b, a))).isEmpty();
        assertThat(OutboxKeyOrder.sendable(List.of(a), List.of(b, a))).containsExactly("a");
    }

    @Test
    void aClaimedRowMissingFromTheReadIsNotSentOnTrust() {
        var p1 = row("p1", "P", 1);
        assertThat(OutboxKeyOrder.sendable(List.of(p1), List.of())).isEmpty();
    }

    @Test
    void theReadSelectsThePendingRowsOfTheKeysUpToTheBound() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE outbox_message_entity (id VARCHAR(255) PRIMARY KEY, timestamp TIMESTAMP, "
                    + "status VARCHAR(64), partition_key VARCHAR(255))");
            insert(jdbc, "p1", "P", "Pending", 1);
            insert(jdbc, "p0", "P", "Sent", 0);
            insert(jdbc, "q1", "Q", "Pending", 2);
            insert(jdbc, "r1", "R", "Pending", 3);
            insert(jdbc, "p9", "P", "Pending", 9);

            var ids = jdbc.query(OutboxKeyOrder.pendingOfKeysSql(2), ps -> {
                ps.setString(1, "P");
                ps.setString(2, "Q");
                ps.setTimestamp(3, Timestamp.valueOf(T0.plusSeconds(5)));
            }, (rs, i) -> rs.getString(1));

            assertThat(ids).containsExactlyInAnyOrder("p1", "q1");
        } finally {
            database.shutdown();
        }
        assertThatThrownBy(() -> OutboxKeyOrder.pendingOfKeysSql(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void insert(JdbcTemplate jdbc, String id, String key, String status, int seconds) {
        jdbc.update("INSERT INTO outbox_message_entity VALUES (?, ?, ?, ?)",
                id, Timestamp.valueOf(T0.plusSeconds(seconds)), status, key);
    }
}
