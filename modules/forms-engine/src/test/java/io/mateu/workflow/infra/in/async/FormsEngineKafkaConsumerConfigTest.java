package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.usecases.canceltask.CancelTaskCommand;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * How the forms engine answers the orchestrator: which events it acts on, and which it lets past.
 *
 * <p>This is the whole of its Kafka-mode inbound contract, and it was untested. The routing looks
 * obvious and is not: the engine dispatches every {@code USER_TASK} with the task id
 * {@code complete-form}, so a form is created on that string and on nothing else, and the form to
 * render is named by a {@code formId} process variable rather than by anything on the event.
 *
 * <p>The handlers run on threads of their own, so these tests wait for the effect rather than
 * asserting it immediately. That asynchrony is worth knowing about beyond the test: the consumer
 * returns before the work happens, so Kafka commits the offset either way and a failure inside one
 * of those threads reaches nobody. The one failure that does surface is the missing {@code formId}
 * below — it is thrown on the consumer's own thread, before the thread is started.
 */
class FormsEngineKafkaConsumerConfigTest {

    /** Long enough for a thread to start on a loaded machine; short enough not to hide a bug. */
    private static final int HANDLED = 5_000;

    private CreateTaskUseCase createTask;
    private CancelTaskUseCase cancelTask;
    private Consumer<DomainEvent> consumer;

    @BeforeEach
    void setUp() {
        createTask = mock(CreateTaskUseCase.class);
        cancelTask = mock(CancelTaskUseCase.class);
        consumer = new FormsEngineKafkaConsumerConfig(createTask, cancelTask)
                .consumeWorkerEventForFormsEngine();
    }

    @Test
    void a_complete_form_task_creates_the_form_named_by_the_formId_variable() {
        consumer.accept(new TaskExecutionRequested("exec-1", "process-1", "booking", "step-1",
                "complete-form", List.of(
                new io.mateu.workflow.dtos.Variable("formId", "checkin"),
                new io.mateu.workflow.dtos.Variable("tenant", "acme"))));

        var captor = ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(createTask, timeout(HANDLED)).handle(captor.capture());
        var command = captor.getValue();
        assertThat(command.stepExecutionId()).isEqualTo("exec-1");
        assertThat(command.processId()).isEqualTo("process-1");
        assertThat(command.workflowDefinitionId()).isEqualTo("booking");
        assertThat(command.stepId()).isEqualTo("step-1");
        assertThat(command.formId()).isEqualTo("checkin");
        // Every variable travels on, formId included: the form's fields are filled from them, and
        // dropping the one that chose the form would be an odd place to start pruning.
        assertThat(command.variables()).containsExactly(
                new Variable("formId", "checkin"), new Variable("tenant", "acme"));
    }

    @Test
    void a_task_that_is_not_complete_form_is_not_a_form_and_is_left_alone() {
        // Everything the engine dispatches lands on this consumer, including the ACTION steps meant
        // for somebody else's worker. Acting on them would create forms nobody asked for.
        consumer.accept(new TaskExecutionRequested("exec-2", "process-1", "booking", "step-2",
                "", List.of(new io.mateu.workflow.dtos.Variable("formId", "checkin"))));

        verify(createTask, never()).handle(any());
    }

    @Test
    void a_complete_form_task_with_no_formId_fails_loudly_rather_than_creating_nothing() {
        // Thrown on the consumer's thread, so the listener fails and the task is redelivered. The
        // alternative — shrugging — would leave a USER_TASK step pending forever with nothing to
        // explain it.
        assertThatThrownBy(() -> consumer.accept(new TaskExecutionRequested(
                "exec-3", "process-1", "booking", "step-3", "complete-form", List.of())))
                .isInstanceOf(NoSuchElementException.class);

        verify(createTask, never()).handle(any());
    }

    @Test
    void a_cancellation_cancels_the_task_it_names() {
        consumer.accept(new TaskCancellationRequested("exec-4"));

        var captor = ArgumentCaptor.forClass(CancelTaskCommand.class);
        verify(cancelTask, timeout(HANDLED)).handle(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo("exec-4");
    }

    @Test
    void an_event_the_forms_engine_has_no_business_with_is_ignored() {
        consumer.accept(new UnrelatedEvent());

        verifyNoInteractions(createTask, cancelTask);
    }

    private record UnrelatedEvent() implements DomainEvent {
    }
}
