package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
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
class ResumeWorkflowUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;
    @Mock ProcessRepository processRepository;
    @Mock ResumeProcessUseCase resumeProcessUseCase;

    @InjectMocks ResumeWorkflowUseCase useCase;

    private WorkflowDefinition definition(boolean paused) {
        return new WorkflowDefinition("wd-1", "Test", 1, null, WorkflowDefinitionStatus.ACTIVE,
                null, false, 0, false, null, 0, List.of(), paused);
    }

    private Process process(String id, String definitionId, ProcessStatus status) {
        return Process.builder().id(id).workflowDefinitionId(definitionId).status(status).build();
    }

    @Test
    void clearsThePausedFlagAndResumesEveryPausedProcessOfTheDefinition() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(definition(true)));
        when(processRepository.findAll()).thenReturn(List.of(
                process("p-paused", "wd-1", ProcessStatus.PAUSED),
                process("p-born-paused", "wd-1", ProcessStatus.PAUSED),
                process("p-running", "wd-1", ProcessStatus.RUNNING),
                process("p-other", "wd-2", ProcessStatus.PAUSED)));

        useCase.handle("wd-1");

        var captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().paused()).isFalse();

        var commandCaptor = ArgumentCaptor.forClass(ResumeProcessCommand.class);
        verify(resumeProcessUseCase, org.mockito.Mockito.times(2)).handle(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues()).extracting(ResumeProcessCommand::processId)
                .containsExactlyInAnyOrder("p-paused", "p-born-paused");
    }

    @Test
    void aNotPausedDefinitionIsNotSavedAgainButItsPausedProcessesAreStillResumed() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(definition(false)));
        when(processRepository.findAll()).thenReturn(List.of(
                process("p-paused", "wd-1", ProcessStatus.PAUSED)));

        useCase.handle("wd-1");

        verify(repository, never()).save(any());
        verify(resumeProcessUseCase).handle(new ResumeProcessCommand("p-paused"));
    }

    @Test
    void failsForUnknownDefinition() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
