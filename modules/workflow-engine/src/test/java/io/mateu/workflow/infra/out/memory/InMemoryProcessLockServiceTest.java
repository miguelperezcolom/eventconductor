package io.mateu.workflow.infra.out.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProcessLockServiceTest {

    private final InMemoryProcessLockService service = new InMemoryProcessLockService();

    @Test
    void tryLockSucceedsFirstTime() {
        assertThat(service.tryLock("p-1")).isTrue();
    }

    @Test
    void tryLockFailsWhenHeldByAnotherThread() throws Exception {
        service.tryLock("p-1");
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread t = new Thread(() -> result.set(service.tryLock("p-1")));
        t.start();
        t.join();
        assertThat(result.get()).isFalse();
    }

    @Test
    void unlockReleasesLock() {
        service.tryLock("p-1");
        service.unlock("p-1");
        assertThat(service.tryLock("p-1")).isTrue();
    }

    @Test
    void unlockForUnknownProcessIdDoesNotThrow() {
        service.unlock("p-missing");
    }

    @Test
    void differentProcessIdsAreLockableIndependently() {
        assertThat(service.tryLock("p-1")).isTrue();
        assertThat(service.tryLock("p-2")).isTrue();
    }
}
