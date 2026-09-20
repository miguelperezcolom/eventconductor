package io.mateu.workflow.worker.embedded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UpdateStepExecutionSinkTest {

    private final UpdateStepExecutionUseCase useCase = mock(UpdateStepExecutionUseCase.class);
    private final UpdateStepExecutionSink sink = new UpdateStepExecutionSink(useCase);
    private final TaskExecutionRequested task =
            new TaskExecutionRequested("tx-1", "p-1", "wf", "step", "place-order@1", List.of());

    private UpdateStepExecutionCommand capture() {
        var captor = ArgumentCaptor.forClass(UpdateStepExecutionCommand.class);
        verify(useCase).handle(captor.capture());
        return captor.getValue();
    }

    @Test
    void running_updates_the_step_to_running_keyed_by_task_execution_id() {
        sink.running(task);

        var command = capture();
        assertThat(command.stepId()).isEqualTo("tx-1");
        assertThat(command.status()).isEqualTo(StepExecutionStatus.RUNNING);
    }

    @Test
    void completed_updates_to_completed_with_domain_variables() {
        sink.completed(task, List.of(new io.mateu.workflow.dtos.Variable("total", "30")));

        var command = capture();
        assertThat(command.status()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(command.variables()).containsExactly(new Variable("total", "30"));
    }

    @Test
    void failed_updates_to_error_and_carries_the_reason_in_the_log() {
        sink.failed(task, List.of(), "OUT_OF_STOCK");

        var command = capture();
        assertThat(command.status()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(command.log()).isEqualTo("OUT_OF_STOCK");
    }
}
