package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.application.sync.Invocation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryInvocationRepositoryTest {

    private final InMemoryInvocationRepository repository = new InMemoryInvocationRepository();

    private static Invocation invocation(String id, String key, LocalDateTime expiresAt) {
        return new Invocation(id, "wd", key, "h", "p-" + id, null, LocalDateTime.now(), expiresAt, null);
    }

    @Test
    void createsAndFindsByIdAndKey() {
        repository.createWith(invocation("i1", "k1", LocalDateTime.now().plusHours(1)), () -> { });
        assertThat(repository.findById("i1")).isPresent();
        assertThat(repository.findByKey("wd", "k1").map(Invocation::id)).contains("i1");
        assertThat(repository.findByKey("other", "k1")).isEmpty();
    }

    @Test
    void aTakenKeyIsADuplicate() {
        repository.createWith(invocation("i1", "k1", LocalDateTime.now().plusHours(1)), () -> { });
        assertThatThrownBy(() -> repository.createWith(invocation("i2", "k1", LocalDateTime.now()), () -> { }))
                .isInstanceOf(InvocationRepository.DuplicateInvocationException.class);
    }

    @Test
    void aCreationThatFailsLeavesNothingBehind() {
        assertThatThrownBy(() -> repository.createWith(invocation("i1", "k1", LocalDateTime.now()),
                () -> { throw new IllegalStateException("no"); })).hasMessage("no");
        assertThat(repository.findById("i1")).isEmpty();
        assertThat(repository.findByKey("wd", "k1")).isEmpty();
    }

    @Test
    void expiredInvocationsArePurged() {
        repository.createWith(invocation("old", "k1", LocalDateTime.now().minusMinutes(1)), () -> { });
        repository.createWith(invocation("new", "k2", LocalDateTime.now().plusHours(1)), () -> { });
        assertThat(repository.purgeExpired(LocalDateTime.now())).isEqualTo(1);
        assertThat(repository.findById("old")).isEmpty();
        assertThat(repository.findByKey("wd", "k1")).isEmpty();
        assertThat(repository.findById("new")).isPresent();
    }

    @Test
    void findsTheInvocationThatStartedAProcessAndForgetsItWhenPurged() {
        repository.createWith(invocation("i1", "k1", LocalDateTime.now().minusMinutes(1)), () -> { });
        repository.createWith(invocation("i2", "k2", LocalDateTime.now().plusHours(1)), () -> { });

        assertThat(repository.findByProcessId("p-i1").map(Invocation::id)).contains("i1");
        assertThat(repository.findByProcessId("p-i2").map(Invocation::id)).contains("i2");
        assertThat(repository.findByProcessId("p-unknown")).isEmpty();

        repository.purgeExpired(LocalDateTime.now());
        assertThat(repository.findByProcessId("p-i1")).isEmpty();
        repository.deleteByProcessIds(java.util.List.of("p-i2"));
        assertThat(repository.findByProcessId("p-i2")).isEmpty();
    }

    @Test
    void aFailedCreationIsNotFoundByItsProcessEither() {
        assertThatThrownBy(() -> repository.createWith(invocation("i1", "k1", LocalDateTime.now()),
                () -> { throw new IllegalStateException("no"); })).hasMessage("no");
        assertThat(repository.findByProcessId("p-i1")).isEmpty();
    }

    @Test
    void creationsWithDifferentKeysDoNotWaitForEachOther() throws Exception {
        // In memory mode the creation runs the whole process inline. One key's creation blocking
        // must not hold up another key's — that serialized every synchronous call in the JVM.
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var slow = new Thread(() -> repository.createWith(invocation("slow", "k-slow", LocalDateTime.now().plusHours(1)), () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        slow.start();
        try {
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var other = java.util.concurrent.CompletableFuture.runAsync(() ->
                    repository.createWith(invocation("fast", "k-fast", LocalDateTime.now().plusHours(1)), () -> { }));
            other.get(5, java.util.concurrent.TimeUnit.SECONDS); // would time out under one global monitor
            assertThat(repository.findById("fast")).isPresent();
        } finally {
            release.countDown();
            slow.join(5_000);
        }
    }

    @Test
    void theSameKeyWaitsForTheCreationInFlightAndIsThenADuplicate() throws Exception {
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new Thread(() -> repository.createWith(invocation("first", "k1", LocalDateTime.now().plusHours(1)), () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        first.start();
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second = java.util.concurrent.CompletableFuture.runAsync(() ->
                repository.createWith(invocation("second", "k1", LocalDateTime.now().plusHours(1)), () -> { }));
        Thread.sleep(200);
        assertThat(second).isNotDone(); // blocked on the key, as it would be on the unique index
        release.countDown();
        first.join(5_000);
        assertThatThrownBy(() -> second.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvocationRepository.DuplicateInvocationException.class);
    }
}
