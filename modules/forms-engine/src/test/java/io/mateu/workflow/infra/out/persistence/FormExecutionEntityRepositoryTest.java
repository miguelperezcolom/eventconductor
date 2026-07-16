package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class FormExecutionEntityRepositoryTest {

    @Autowired
    FormExecutionEntityRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.saveAll(List.of(
                task("t1", "PENDING", null),
                task("t2", "PENDING", ""),
                task("t3", "PENDING", "alice"),
                task("t4", "PENDING", "bob"),
                task("t5", "COMPLETED", null)
        ));
    }

    private FormExecutionEntity task(String id, String status, String userId) {
        return new FormExecutionEntity(id, "form1", "process1", "step1", "se-" + id,
                "{}", "{}", status, userId, null);
    }

    @Test
    void findsPendingTasksUnassignedOrAssignedToUser() {
        var page = repository.findTaskSummariesByStatusAndUser(
                List.of("PENDING"), "alice", PageRequest.of(0, 10, Sort.by("id")));

        assertThat(page.getContent())
                .extracting(FormExecutionEntityRepository.TaskSummary::getId)
                .containsExactly("t1", "t2", "t3");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void projectionExposesListingFields() {
        var page = repository.findTaskSummariesByStatusAndUser(
                List.of("PENDING"), "alice", PageRequest.of(0, 1, Sort.by("id")));

        var task = page.getContent().getFirst();
        assertThat(task.getId()).isEqualTo("t1");
        assertThat(task.getFormId()).isEqualTo("form1");
        assertThat(task.getProcessId()).isEqualTo("process1");
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getUserId()).isNull();
    }

    @Test
    void claimAssignsUnassignedTasksButSkipsTasksOwnedByOthers() {
        var claimed = repository.claim(List.of("t1", "t2", "t3", "t4"), "alice");

        assertThat(claimed).isEqualTo(3); // t1, t2 and t3 (already alice's); t4 stays bob's
        assertThat(repository.findById("t1").orElseThrow().getUserId()).isEqualTo("alice");
        assertThat(repository.findById("t2").orElseThrow().getUserId()).isEqualTo("alice");
        assertThat(repository.findById("t3").orElseThrow().getUserId()).isEqualTo("alice");
        assertThat(repository.findById("t4").orElseThrow().getUserId()).isEqualTo("bob");
    }
}
