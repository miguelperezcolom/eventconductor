package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.aggregates.LogMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLogMessageRepositoryTest {

    private final InMemoryLogMessageRepository repo = new InMemoryLogMessageRepository();

    private LogMessage msg(String id, String processId) {
        return new LogMessage(id, LocalDateTime.now(), processId, "se-1", "INFO", "message", "system");
    }

    @Test
    void savesAndFindsById() {
        repo.save(msg("1", "p-1"));
        assertThat(repo.findById("1")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAllSaved() {
        repo.save(msg("1", "p-1"));
        repo.save(msg("2", "p-2"));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(msg("1", "p-1"));
        repo.save(msg("2", "p-2"));
        repo.deleteAllById(List.of("1"));
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById("1")).isEmpty();
    }

    @Test
    void findByProcessIdFiltersCorrectly() {
        repo.save(msg("1", "p-1"));
        repo.save(msg("2", "p-2"));
        repo.save(msg("3", "p-1"));
        List<LogMessage> result = repo.findByProcessId("p-1");
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(m -> "p-1".equals(m.getProcessId()));
    }
}
