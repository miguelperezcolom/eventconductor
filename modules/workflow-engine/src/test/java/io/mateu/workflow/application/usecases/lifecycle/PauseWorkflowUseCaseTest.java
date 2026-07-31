package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PauseWorkflowUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;
    @Mock ProcessRepository processRepository;
    @Mock PauseProcessUseCase pauseProcessUseCase;

    @InjectMocks PauseWorkflowUseCase useCase;

    private WorkflowDefinition definition(boolean paused) {
        return new WorkflowDefinition("wd-1", "Test", 1, null, WorkflowDefinitionStatus.ACTIVE,
                null, false, 0, false, null, 0, List.of(), paused);
    }

    private Process process(String id, String definitionId, ProcessStatus status) {
        return Process.builder().id(id).workflowDefinitionId(definitionId).status(status).build();
    }

    @Test
    void setsThePausedFlagAndPausesEveryPendingOrRunningProcessOfTheDefinition() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(definition(false)));
        when(processRepository.findAll()).thenReturn(List.of(
                process("p-pending", "wd-1", ProcessStatus.PENDING),
                process("p-running", "wd-1", ProcessStatus.RUNNING),
                process("p-completed", "wd-1", ProcessStatus.COMPLETED),
                process("p-paused", "wd-1", ProcessStatus.PAUSED),
                process("p-other", "wd-2", ProcessStatus.RUNNING)));

        useCase.handle("wd-1");

        var captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().paused()).isTrue();

        var commandCaptor = ArgumentCaptor.forClass(PauseProcessCommand.class);
        verify(pauseProcessUseCase, org.mockito.Mockito.times(2)).handle(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues()).extracting(PauseProcessCommand::processId)
                .containsExactlyInAnyOrder("p-pending", "p-running");
    }

    @Test
    void anAlreadyPausedDefinitionIsNotSavedAgainButItsProcessesAreStillSwept() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(definition(true)));
        when(processRepository.findAll()).thenReturn(List.of(
                process("p-running", "wd-1", ProcessStatus.RUNNING)));

        useCase.handle("wd-1");

        verify(repository, never()).save(any());
        verify(pauseProcessUseCase).handle(new PauseProcessCommand("p-running"));
    }

    @Test
    void failsForUnknownDefinition() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
