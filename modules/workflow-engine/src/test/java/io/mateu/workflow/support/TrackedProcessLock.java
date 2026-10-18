package io.mateu.workflow.support;

import io.mateu.workflow.application.out.ProcessLockService;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code ProcessLockService.runExclusively} stub that grants the lock, runs the action, and
 * remembers whether it is currently inside it — so a test can assert that a read or a write
 * happens while the lock is held, not merely that the lock was taken at some point.
 */
public final class TrackedProcessLock {

    private final AtomicBoolean held = new AtomicBoolean();

    /** Answer for {@code runExclusively}: holds the lock for exactly the action's duration. */
    public Answer<Boolean> granted() {
        return invocation -> {
            held.set(true);
            try {
                invocation.getArgument(1, Runnable.class).run();
            } finally {
                held.set(false);
            }
            return true;
        };
    }

    /** Fails the calling test unless the lock is held right now; for use inside other stubs. */
    public void assertHeld(String what) {
        assertThat(held).as(what + " only while the process lock is held").isTrue();
    }

    /** Stubs the lock service as a lock that can never be had. */
    public static void refused(ProcessLockService lockService) {
        org.mockito.Mockito.when(lockService.runExclusively(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
    }
}
