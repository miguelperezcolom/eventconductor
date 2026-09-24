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
}
