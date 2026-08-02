package io.mateu.workflow.infra.out.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProcessLockServiceTest {

    private final InMemoryProcessLockService service = new InMemoryProcessLockService();

    @BeforeEach
    void shortTimeout() {
        // The waiting-behaviour tests must not sit through the production default.
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 1);
    }

    @Test
    void runsTheActionAndReportsSuccess() {
        var ran = new AtomicBoolean();
        assertThat(service.runExclusively("p-1", () -> ran.set(true))).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    void releasesTheProcessOnceTheActionReturns() {
        service.runExclusively("p-1", () -> {});
        assertThat(service.runExclusively("p-1", () -> {})).isTrue();
    }

    @Test
    void releasesTheProcessEvenWhenTheActionThrows() {
        try {
            service.runExclusively("p-1", () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // the action's failure is the caller's to handle; the lock is not
        }
        assertThat(service.runExclusively("p-1", () -> {})).isTrue();
    }

    @Test
    void doesNotRunTheActionWhileAnotherThreadHoldsTheProcess() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> service.runExclusively("p-1", () -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        held.await();

        var ran = new AtomicBoolean();
        assertThat(service.runExclusively("p-1", () -> ran.set(true))).isFalse();
        assertThat(ran).as("the action must not run without exclusivity").isFalse();

        release.countDown();
        holder.join();
    }

    @Test
    void differentProcessesDoNotBlockEachOther() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> service.runExclusively("p-1", () -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        held.await();

        assertThat(service.runExclusively("p-2", () -> {})).isTrue();

        release.countDown();
        holder.join();
    }

    @Test
    void isReentrantWithinTheSameThread() {
        // The JPA implementation is reentrant because a transaction can re-lock its own row;
        // this one must not be stricter than that.
        var inner = new AtomicBoolean();
        service.runExclusively("p-1", () -> service.runExclusively("p-1", () -> inner.set(true)));
        assertThat(inner).isTrue();
    }
}
