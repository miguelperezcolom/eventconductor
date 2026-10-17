package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.usecases.lock.ExpireLockLeasesUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The lease reaper expires leases only on the pod holding its lock, and keeps going after a failure. */
class LockLeaseReaperTest {

    private final ExpireLockLeasesUseCase expire = mock(ExpireLockLeasesUseCase.class);
    private final FakeLeaderLock lock = new FakeLeaderLock();
    private final LockLeaseReaper reaper = new LockLeaseReaper(expire, lock.jdbcTemplate, lock);

    @AfterEach
    void tearDown() {
        reaper.stop();
    }

    @Test
    void theLeaderReapsOnEveryScanAndReleasesTheLock() throws Exception {
        reaper.scanIntervalMs = 20;
        doThrow(new IllegalStateException("blip")).doNothing().when(expire).handle();

        reaper.start();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(expire, atLeast(3)).handle());
        reaper.stop();
        Thread.sleep(50);

        assertThat(lock.locked).containsOnly(888999111L);
        assertThat(lock.unlocked).hasSameSizeAs(lock.locked);
    }

    @Test
    void aPodThatIsNotTheLeaderReapsNothing() {
        reaper.scanIntervalMs = 20;
        lock.leader = false;

        reaper.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> lock.attempts.get() >= 3);

        verify(expire, never()).handle();
        assertThat(lock.unlocked).isEmpty();
    }
}
