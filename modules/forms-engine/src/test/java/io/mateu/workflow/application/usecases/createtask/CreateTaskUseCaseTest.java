package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Variable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateTaskUseCaseTest {

    @Mock FormExecutionRepository formExecutionRepository;
    @Mock FormsMetrics formsMetrics;
    @Mock io.mateu.workflow.application.services.HumanTaskEvents humanTaskEvents;

    @InjectMocks CreateTaskUseCase useCase;

    @Test
    void createsFormExecutionWithPendingStatus() {
        // CreateTaskCommand(stepExecutionId, processId, workflowDefinitionId, stepId, formId, variables)
        var command = new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1",
                List.of(new Variable("k", "v")));

        useCase.handle(command);

        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        FormExecution saved = captor.getValue();
        assertThat(saved.formId()).isEqualTo("form-1");
        assertThat(saved.processId()).isEqualTo("p-1");
        assertThat(saved.stepId()).isEqualTo("s1");
        assertThat(saved.stepExecutionId()).isEqualTo("se-1");
        assertThat(saved.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(saved.variables()).hasSize(1);
        assertThat(saved.id()).isNotNull();
    }

    @Test
    void anOpenedTaskIsAnnounced() {
        useCase.handle(new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", List.of()));

        ArgumentCaptor<FormExecution> saved = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(saved.capture());
        verify(humanTaskEvents).changed(saved.getValue());
    }

    @Test
    void createsFormExecutionWithEmptyValues() {
        var command = new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", List.of());

        useCase.handle(command);

        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().values()).isEmpty();
    }
}
