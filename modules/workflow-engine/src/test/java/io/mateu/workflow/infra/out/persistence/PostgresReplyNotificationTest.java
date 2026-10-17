package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.sync.SyncReplyWaiters;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The half of {@link PostgresReplyNotification} that needs no PostgreSQL: on any other database, or
 * switched off, it stays idle — no listener, and no NOTIFY attempted — and the waiters' poll does
 * the job alone. The live path is {@code PostgresReplyNotificationPostgresTest}.
 */
class PostgresReplyNotificationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final SyncReplyWaiters waiters = mock(SyncReplyWaiters.class);

    @Test
    void onAnotherDatabaseNothingListensAndNothingIsSent() {
        var notification = new PostgresReplyNotification(jdbc, dataSource, new H2DbLockDialect(), waiters);
        notification.start();

        assertThat(notification.isActive()).isFalse();
        notification.replyRecorded("p-1");
        notification.stop();

        verifyNoInteractions(jdbc, dataSource, waiters);
    }

    @Test
    void switchedOffItStaysIdleEvenOnPostgres() {
        var notification = new PostgresReplyNotification(jdbc, dataSource, new PostgresDbLockDialect(), waiters);
        notification.enabled = false;
        notification.start();

        assertThat(notification.isActive()).isFalse();
        notification.replyRecorded("p-1");
        notification.stop();

        verifyNoInteractions(jdbc, dataSource, waiters);
    }
}
