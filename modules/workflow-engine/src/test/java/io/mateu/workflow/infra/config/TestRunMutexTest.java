package io.mateu.workflow.infra.config;

import io.mateu.workflow.application.out.*;
import io.mateu.workflow.infra.out.memory.InMemoryLockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TestRunMutexTest {
    @Test void competingSubmissionsAreExcludedBeforeCreationAndFailuresReleaseTheMutex() throws Exception {
        var named = new InMemoryLockService(); ReflectionTestUtils.setField(named,"leaseMs",900000L);
        ProcessLockService processLocks = (id, action) -> { action.run(); return true; };
        var firstPod = new WorkflowTestRunConfiguration().testRunMutex(named,processLocks);
        var secondPod = new WorkflowTestRunConfiguration().testRunMutex(named,processLocks);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var creation = executor.submit(() -> firstPod.run("new-id", () -> {
                entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> secondPod.run("new-id", () -> fail("A second creator entered")))
                    .isInstanceOf(ConcurrentProcessAccessException.class);
            } finally { release.countDown(); }
            creation.get(5,TimeUnit.SECONDS);
        }
        assertThatThrownBy(() -> firstPod.run("new-id", () -> { throw new IllegalArgumentException("rejected"); }))
            .hasMessage("rejected");
        secondPod.run("new-id", () -> {});
    }
}
