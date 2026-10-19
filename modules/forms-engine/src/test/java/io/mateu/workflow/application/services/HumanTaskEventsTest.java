package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HumanTaskEventsTest {

    @Mock StreamBridge streamBridge;
    @Mock FormRepository formRepository;
    @Mock FormsMetrics formsMetrics;

    static FormExecution task(FormExecutionStatus status) {
        return FormExecution.builder().id("t-1").formId("approve").processId("p-1").stepId("s1")
                .status(status).userId("alice").variables(List.of()).values(List.of()).build();
    }

    private HumanTaskEvents events() {
        return new HumanTaskEvents(streamBridge, formRepository, formsMetrics);
    }

    private Message<?> sent() {
        var sent = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge).send(eq(HumanTaskEvents.BINDING), sent.capture());
        return (Message<?>) sent.getValue();
    }

    @Test
    void theChangeCarriesTheFormsNameAndWhoMayWorkOnIt() {
        when(formRepository.findById("approve")).thenReturn(Optional.of(
                new Form("approve", "Approve the refund", "", List.of(), List.of(), List.of("finance"))));
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(true);

        events().changed(task(FormExecutionStatus.PENDING), HumanTaskChanged.CREATED);

        var event = (HumanTaskChanged) sent().getPayload();
        assertThat(event.taskId()).isEqualTo("t-1");
        assertThat(event.formName()).isEqualTo("Approve the refund");
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.requiredRoles()).containsExactly("finance");
        assertThat(event.processId()).isEqualTo("p-1");
        assertThat(event.at()).isNotNull();
        assertThat(event.event()).isEqualTo("created");
        verify(formsMetrics).taskEvent("created", "approve");
    }

    /**
     * Published as a binary CloudEvent, so an event trigger on the destination this topic is mapped to
     * can start a workflow on one kind of change — {@code io.eventconductor.task.escalated} — without
     * opening the record.
     */
    @Test
    void theChangeIsACloudEventTypedByWhatHappened() {
        when(formRepository.findById("approve")).thenReturn(Optional.empty());
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(true);
        var due = LocalDateTime.of(2026, 10, 20, 9, 0);
        var escalated = task(FormExecutionStatus.ASSIGNED).withPriority(80).withDueDate(due)
                .withCandidateGroups(List.of("managers")).withEscalationLevel(2).withBusinessKey("order-7");

        events().changed(escalated, HumanTaskChanged.ESCALATED);

        var message = sent();
        assertThat(message.getHeaders().get("ce_type")).isEqualTo("io.eventconductor.task.escalated");
        assertThat(message.getHeaders().get("ce_source")).isEqualTo(HumanTaskEvents.SOURCE);
        assertThat(message.getHeaders().get("ce_subject")).isEqualTo("t-1");
        assertThat(message.getHeaders().get("ce_specversion")).isEqualTo("1.0");
        assertThat(message.getHeaders().get("ce_processid")).isEqualTo("p-1");
        assertThat(message.getHeaders().get("ce_id")).isNotNull();
        var event = (HumanTaskChanged) message.getPayload();
        assertThat(event.priority()).isEqualTo(80);
        assertThat(event.dueDate()).isEqualTo(due.toInstant(ZoneOffset.UTC));
        assertThat(event.candidateGroups()).containsExactly("managers");
        assertThat(event.escalationLevel()).isEqualTo(2);
        assertThat(event.businessKey()).isEqualTo("order-7");
        assertThat(event.cloudEventType()).isEqualTo("io.eventconductor.task.escalated");
    }

    @Test
    void aBrokerThatRefusesItNeverStopsTheTask() {
        when(formRepository.findById("approve")).thenReturn(Optional.empty());
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> events().changed(task(FormExecutionStatus.COMPLETED), HumanTaskChanged.COMPLETED))
                .doesNotThrowAnyException();
    }

    @Test
    void aBrokerThatDeclinesTheEventIsLoggedNotThrown() {
        when(formRepository.findById("approve")).thenReturn(Optional.empty());
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(false);

        assertThatCode(() -> events().changed(task(FormExecutionStatus.CANCELLED), HumanTaskChanged.CANCELLED))
                .doesNotThrowAnyException();
        verify(streamBridge).send(eq(HumanTaskEvents.BINDING), any());
    }

    @Test
    void aTaskWhoseFormIsUnknownIsAnnouncedUnderItsFormIdAndRequiresNoRole() {
        when(formRepository.findById("approve")).thenReturn(Optional.empty());
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(true);

        events().changed(task(FormExecutionStatus.PENDING), HumanTaskChanged.CREATED);

        var event = (HumanTaskChanged) sent().getPayload();
        assertThat(event.formName()).isEqualTo("approve");
        assertThat(event.requiredRoles()).isEmpty();
    }

    @Test
    void aTaskWithoutFormOrStatusIsStillAnnounced() {
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(true);
        var bare = FormExecution.builder().id("t-2").stepId("s1")
                .variables(List.of()).values(List.of()).build();

        events().changed(bare, null);

        var message = sent();
        var event = (HumanTaskChanged) message.getPayload();
        assertThat(event.taskId()).isEqualTo("t-2");
        assertThat(event.formId()).isNull();
        assertThat(event.status()).isNull();
        assertThat(event.cloudEventType()).isEqualTo("io.eventconductor.task.changed");
        assertThat(message.getHeaders().get("ce_processid")).isEqualTo("");
        org.mockito.Mockito.verifyNoInteractions(formRepository);
    }

    @Test
    void theOldShapeOfTheEventStillBuilds() {
        var event = new HumanTaskChanged("t", "f", "F", "p", "s", "PENDING", List.of(), null, null);

        assertThat(event.event()).isNull();
        assertThat(event.candidateGroups()).isEmpty();
    }
}
