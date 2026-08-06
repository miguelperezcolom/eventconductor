package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InMemoryProcessRepositoryTest {

    @Mock ProcessDomainEventUseCase processDomainEventUseCase;

    InMemoryProcessRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryProcessRepository();
        ReflectionTestUtils.setField(repo, "processDomainEventUseCase", processDomainEventUseCase);
        // Read model off: announceIfChanged short-circuits, so save behaves as before.
        ReflectionTestUtils.setField(repo, "processStatusAnnouncer",
                new io.mateu.workflow.application.services.ProcessStatusAnnouncer(false));
    }

    private Process process(String id, String businessKey) {
        return Process.builder().id(id).businessKey(businessKey)
                .status(ProcessStatus.PENDING).variables(List.of()).build();
    }

    @Test
    void savesAndFindsById() {
        repo.save(process("p-1", "BK-1"));
        assertThat(repo.findById("p-1")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        repo.deleteAllById(List.of("p-1"));
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById("p-1")).isEmpty();
    }

    @Test
    void findByBusinessKeyReturnsMatchingProcess() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        assertThat(repo.findByBusinessKey("BK-1")).isPresent()
                .get().extracting(Process::id).isEqualTo("p-1");
    }

    @Test
    void findByBusinessKeyReturnsEmptyWhenNotFound() {
        assertThat(repo.findByBusinessKey("MISSING")).isEmpty();
    }
}
