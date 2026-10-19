package io.mateu.workflow.application.usecases.canceltask;

import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.infra.out.memory.InMemoryFormExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CancelTaskUseCaseTest {

    InMemoryFormExecutionRepository repository;
    FormsMetrics formsMetrics;
    HumanTaskEvents humanTaskEvents;
    CancelTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFormExecutionRepository();
        formsMetrics = mock(FormsMetrics.class);
        humanTaskEvents = mock(HumanTaskEvents.class);
        useCase = new CancelTaskUseCase(repository, formsMetrics, humanTaskEvents);
    }

    private static FormExecution task(String id, String stepExecutionId, FormExecutionStatus status) {
        return FormExecution.builder().id(id).formId("form-1").stepExecutionId(stepExecutionId).status(status)
                .variables(List.of()).values(List.of()).build();
    }

    @Test
    void cancelsTheOpenTasksOfTheStepExecution() {
        repository.save(task("fe-1", "se-1", FormExecutionStatus.PENDING));
        repository.save(task("fe-2", "se-1", FormExecutionStatus.ASSIGNED));
        repository.save(task("fe-3", "se-2", FormExecutionStatus.PENDING));

        useCase.handle(new CancelTaskCommand("se-1"));

        assertThat(repository.findById("fe-1").orElseThrow().status()).isEqualTo(FormExecutionStatus.CANCELLED);
        assertThat(repository.findById("fe-2").orElseThrow().status()).isEqualTo(FormExecutionStatus.CANCELLED);
        assertThat(repository.findById("fe-3").orElseThrow().status()).isEqualTo(FormExecutionStatus.PENDING);
        verify(formsMetrics, org.mockito.Mockito.times(2)).taskCancelled(org.mockito.ArgumentMatchers.eq("form-1"), any());
        verify(humanTaskEvents).changed(repository.findById("fe-1").orElseThrow(), HumanTaskChanged.CANCELLED);
    }

    /** What someone submitted is not rewritten as never having happened. */
    @Test
    void aCompletedTaskKeepsItsStatus() {
        repository.save(task("fe-1", "se-1", FormExecutionStatus.COMPLETED));

        useCase.handle(new CancelTaskCommand("se-1"));

        assertThat(repository.findById("fe-1").orElseThrow().status()).isEqualTo(FormExecutionStatus.COMPLETED);
        verify(humanTaskEvents, never()).changed(any(), any());
    }

    @Test
    void doesNothingWhenNoFormExecutionsFound() {
        useCase.handle(new CancelTaskCommand("se-unknown"));

        verify(formsMetrics, never()).taskCancelled(any(), any());
    }
}
