package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The cross-pod reply wake-up over a real PostgreSQL {@code LISTEN/NOTIFY}: a reply recorded on one
 * pod wakes the waiters on another, the notice is exactly as durable as the transaction that raised
 * it, and the listener survives losing its connection. Skipped without Docker.
 */
class PostgresReplyNotificationPostgresTest {

    private HikariDataSource dataSource;
    private SyncReplyWaiters waiters;
    private PostgresReplyNotification listeningPod;
    private PostgresReplyNotification notifyingPod;
    private JdbcTemplate jdbc;

    @BeforeEach
    void pods() {
        dataSource = PostgresTestDatabase.freshDatabase();
        jdbc = new JdbcTemplate(dataSource);
        waiters = mock(SyncReplyWaiters.class);
        // Two "pods" on one database: one listens, the other records replies.
        listeningPod = new PostgresReplyNotification(jdbc, dataSource, new PostgresDbLockDialect(), waiters);
        listeningPod.start();
        notifyingPod = new PostgresReplyNotification(jdbc, dataSource, new PostgresDbLockDialect(),
                mock(SyncReplyWaiters.class));
        notifyingPod.start();
    }

    @AfterEach
    void stop() {
        if (listeningPod != null) {
            listeningPod.stop();
        }
        if (notifyingPod != null) {
            notifyingPod.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /** Notifies until the listener has heard one — LISTEN is issued asynchronously, on its own thread. */
    private void awaitListening() {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            notifyingPod.replyRecorded("primer");
            verify(waiters, atLeastOnce()).wakeFromNotification("primer");
        });
    }

    @Test
    void onPostgresBothHalvesAreActive() {
        assertThat(listeningPod.isActive()).isTrue();
        assertThat(notifyingPod.isActive()).isTrue();
    }

    @Test
    void aReplyRecordedOnOnePodWakesTheWaitersOnAnother() {
        awaitListening();

        notifyingPod.replyRecorded("process-42");

        verify(waiters, org.mockito.Mockito.timeout(5_000)).wakeFromNotification("process-42");
    }

    @Test
    void theNoticeIsDeliveredOnCommitAndNeverOnRollback() {
        awaitListening();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        tx.executeWithoutResult(status -> {
            notifyingPod.replyRecorded("rolled-back");
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> notifyingPod.replyRecorded("committed"));

        verify(waiters, org.mockito.Mockito.timeout(5_000)).wakeFromNotification("committed");
        // Notifications arrive in commit order: had the rolled-back one been sent, it would be here.
        verify(waiters, never()).wakeFromNotification("rolled-back");
    }

    @Test
    void theListenerReconnectsAfterLosingItsConnection() {
        awaitListening();

        // Kill every other session on the database — the listener's dedicated connection among them.
        jdbc.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND pid <> pg_backend_pid()");

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            notifyingPod.replyRecorded("after-reconnect");
            verify(waiters, atLeastOnce()).wakeFromNotification("after-reconnect");
        });
    }

    @Test
    void aFailedNotifyIsSwallowedBecauseThePollStillCatchesTheReply() {
        var closed = PostgresTestDatabase.freshDatabase();
        closed.close();
        var pod = new PostgresReplyNotification(new JdbcTemplate(closed), dataSource,
                new PostgresDbLockDialect(), mock(SyncReplyWaiters.class));
        pod.start();
        try {
            assertThat(pod.isActive()).isTrue();
            pod.replyRecorded("p-1"); // must not throw: the reply is committed regardless
        } finally {
            pod.stop();
        }
    }
}
