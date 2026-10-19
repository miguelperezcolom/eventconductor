package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An escalation goes to the topic its task was dispatched to — where the forms engine took the task. */
class HumanTaskEscalationRequestedHandlerTest {

    StepExecutionRepository steps = mock(StepExecutionRepository.class);
    DownstreamEventPublisher downstream = mock(DownstreamEventPublisher.class);
    HumanTaskEscalationRequestedHandler handler = new HumanTaskEscalationRequestedHandler(steps, downstream);

    @Test
    void itTravelsOnTheTasksTopic() {
        var step = JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "f", "topic": "forms-eu"}""", Step.class);
        var execution = StepExecution.create(step, "p-1", 0);
        when(steps.findById(execution.id())).thenReturn(Optional.of(execution));
        var event = new HumanTaskEscalationRequested(execution.id(), "p-1", "approve", 1, "notify", null, null, "now");

        handler.handle(event);

        verify(downstream).publish(event, "forms-eu");
        assertThat(handler.eventClass()).isEqualTo(HumanTaskEscalationRequested.class);
    }

    @Test
    void aTaskThatIsGoneStillGetsItOnTheDefaultTopic() {
        var event = new HumanTaskEscalationRequested("gone", "p-1", "approve", 1, "notify", null, null, "now");
        when(steps.findById("gone")).thenReturn(Optional.empty());

        handler.handle(event);

        verify(downstream).publish(event, null);
    }
}
