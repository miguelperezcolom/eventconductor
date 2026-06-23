package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.aggregates.Resource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryResourceRepositoryTest {

    private final InMemoryResourceRepository repo = new InMemoryResourceRepository();

    private Resource resource(String id, String processId) {
        return new Resource(id, LocalDateTime.now(), processId, "se-1", "file", "report.pdf", "http://example.com");
    }

    @Test
    void savesAndFindsById() {
        repo.save(resource("1", "p-1"));
        assertThat(repo.findById("1")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(resource("1", "p-1"));
        repo.save(resource("2", "p-2"));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(resource("1", "p-1"));
        repo.save(resource("2", "p-2"));
        repo.deleteAllById(List.of("2"));
        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void findByProcessIdFiltersCorrectly() {
        repo.save(resource("1", "p-1"));
        repo.save(resource("2", "p-2"));
        repo.save(resource("3", "p-1"));
        assertThat(repo.findByProcessId("p-1")).hasSize(2);
    }
}
