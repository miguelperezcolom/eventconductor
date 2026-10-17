package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The analytics read model on PostgreSQL, the database production runs it on. Skipped without Docker.
 */
class AnalyticsReadModelPostgresTest extends AnalyticsReadModelContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    DbLockDialect dialect() {
        return new PostgresDbLockDialect();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }

    /**
     * Every outbox relay holds the relay gate (a shared advisory lock) while it drains, which under
     * load is nearly always. The projector's own lock must be a different key: on the gate's key its
     * exclusive try fails for as long as any relay is draining — the read model never folds — and,
     * while it does hold it, every relay on every pod finds the gate taken and stops publishing.
     */
    @Test
    void theProjectorFoldsWhileARelayIsDraining() throws Exception {
        seed();
        var relayDialect = new PostgresDbLockDialect();
        try (var relayConnection = ((HikariDataSource) dataSource).getConnection()) {
            assertThat(relayDialect.tryRelayGate(relayConnection)).isTrue();
            var projector = projector(dialect());
            projector.intervalMs = 50;
            try {
                projector.start();
                await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(createdCount()).isEqualTo(4));
            } finally {
                projector.stop();
                relayDialect.releaseRelayGate(relayConnection);
            }
        }
    }
}
