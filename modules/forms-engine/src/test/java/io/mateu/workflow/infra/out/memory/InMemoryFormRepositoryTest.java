package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFormRepositoryTest {

    private final InMemoryFormRepository repo = new InMemoryFormRepository();

    private Form form(String id) {
        return new Form(id, "Form " + id, "Description", List.of());
    }

    @Test
    void savesAndFindsById() {
        repo.save(form("f-1"));
        assertThat(repo.findById("f-1")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(form("1"));
        repo.save(form("2"));
        repo.save(form("3"));
        assertThat(repo.findAll()).hasSize(3);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(form("1"));
        repo.save(form("2"));
        repo.deleteAllById(List.of("1", "2"));
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void saveReturnsId() {
        String id = repo.save(form("f-99"));
        assertThat(id).isEqualTo("f-99");
    }

    @Test
    void updateOverwritesPreviousEntry() {
        repo.save(form("f-1"));
        var updated = new Form("f-1", "Updated", "new desc", List.of());
        repo.save(updated);
        assertThat(repo.findById("f-1").get().name()).isEqualTo("Updated");
    }
}
