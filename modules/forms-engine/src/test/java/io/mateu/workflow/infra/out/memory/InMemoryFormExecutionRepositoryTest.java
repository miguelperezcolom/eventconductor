package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFormExecutionRepositoryTest {

    private final InMemoryFormExecutionRepository repo = new InMemoryFormExecutionRepository();

    private FormExecution fe(String id) {
        return FormExecution.builder()
                .id(id).formId("f-1").processId("p-1")
                .status(FormExecutionStatus.PENDING)
                .variables(List.of()).values(List.of()).build();
    }

    @Test
    void savesAndFindsById() {
        repo.save(fe("fe-1"));
        assertThat(repo.findById("fe-1")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(fe("1"));
        repo.save(fe("2"));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(fe("1"));
        repo.save(fe("2"));
        repo.deleteAllById(List.of("1"));
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById("1")).isEmpty();
    }

    @Test
    void saveReturnsId() {
        String returned = repo.save(fe("fe-42"));
        assertThat(returned).isEqualTo("fe-42");
    }
}
