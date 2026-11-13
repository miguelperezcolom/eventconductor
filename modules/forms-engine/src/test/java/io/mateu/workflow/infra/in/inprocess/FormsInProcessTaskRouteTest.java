package io.mateu.workflow.infra.in.inprocess;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskCommand;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The forms engine's side of a USER_TASK when the workflow engine is embedded in the same JVM. */
class FormsInProcessTaskRouteTest {

    final CreateTaskUseCase createTask = mock(CreateTaskUseCase.class);
    final CancelTaskUseCase cancelTask = mock(CancelTaskUseCase.class);
    final EscalateTaskUseCase escalateTask = mock(EscalateTaskUseCase.class);
    final FormExecutionRepository executions = mock(FormExecutionRepository.class);
    final FormsInProcessTaskRoute route = new FormsInProcessTaskRoute(createTask, cancelTask, escalateTask, executions);

    static TaskExecutionRequested task(String taskId, List<Variable> variables) {
        return new TaskExecutionRequested("step-execution-1", "process-1", "booking", "approve", taskId, variables);
    }

    @Test
    void aUserTaskWithAFormBecomesATaskInTheInbox() {
        assertThat(route.take(task("complete-form", List.of(new Variable("formId", "approval"),
                new Variable("amount", "1500"))), "forms")).isTrue();

        var created = ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(createTask).handle(created.capture());
        assertThat(created.getValue().stepExecutionId()).isEqualTo("step-execution-1");
        assertThat(created.getValue().formId()).isEqualTo("approval");
        assertThat(created.getValue().stepId()).isEqualTo("approve");
        assertThat(created.getValue().variables()).extracting(io.mateu.workflow.domain.Variable::name)
                .containsExactly("formId", "amount");
    }

    @Test
    void aUserTaskWithoutAFormAndEveryOtherTaskAreLeftToTheApplication() {
        assertThat(route.take(task("complete-form", List.of()), null)).isFalse();
        assertThat(route.take(task("complete-form", null), null)).isFalse();
        assertThat(route.take(task("charge-card", List.of(new Variable("formId", "x"))), null)).isFalse();
        assertThat(route.take(new TaskStatusChanged("s", TaskStatus.COMPLETED, List.of(), "p"), null)).isFalse();
        verifyNoInteractions(createTask);
    }

    @Test
    void anEscalationIsTheFormsEngines() {
        var escalation = new HumanTaskEscalationRequested("step-execution-1", "process-1", "approve", 1,
                HumanTaskEscalationRequested.REASSIGN, null, null, "now");

        assertThat(route.take(escalation, null)).isTrue();

        verify(escalateTask).handle(escalation);
    }

    @Test
    void aCancellationIsTakenOnlyForAStepItHoldsATaskFor() {
        when(executions.findByStepExecutionId("mine")).thenReturn(List.of(FormExecution.builder().id("t").build()));
        when(executions.findByStepExecutionId("not-mine")).thenReturn(List.of());

        assertThat(route.take(new TaskCancellationRequested("not-mine", null), null)).isFalse();
        assertThat(route.take(new TaskCancellationRequested("mine", null), null)).isTrue();

        var cancelled = ArgumentCaptor.forClass(CancelTaskCommand.class);
        verify(cancelTask).handle(cancelled.capture());
        assertThat(cancelled.getValue().taskId()).isEqualTo("mine");
    }
}
