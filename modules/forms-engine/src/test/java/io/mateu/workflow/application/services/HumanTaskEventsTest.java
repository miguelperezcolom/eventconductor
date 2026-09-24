package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormRepository;
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

    static FormExecution task(FormExecutionStatus status) {
        return FormExecution.builder().id("t-1").formId("approve").processId("p-1").stepId("s1")
                .status(status).userId("alice").variables(List.of()).values(List.of()).build();
    }

    @Test
    void theChangeCarriesTheFormsNameAndWhoMayWorkOnIt() {
        when(formRepository.findById("approve")).thenReturn(Optional.of(
                new Form("approve", "Approve the refund", "", List.of(), List.of(), List.of("finance"))));
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenReturn(true);

        new HumanTaskEvents(streamBridge, formRepository).changed(task(FormExecutionStatus.PENDING));

        var sent = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge).send(eq(HumanTaskEvents.BINDING), sent.capture());
        var event = (HumanTaskChanged) sent.getValue();
        assertThat(event.taskId()).isEqualTo("t-1");
        assertThat(event.formName()).isEqualTo("Approve the refund");
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.requiredRoles()).containsExactly("finance");
        assertThat(event.processId()).isEqualTo("p-1");
        assertThat(event.at()).isNotNull();
    }

    @Test
    void aBrokerThatRefusesItNeverStopsTheTask() {
        when(formRepository.findById("approve")).thenReturn(Optional.empty());
        when(streamBridge.send(eq(HumanTaskEvents.BINDING), any())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> new HumanTaskEvents(streamBridge, formRepository).changed(task(FormExecutionStatus.COMPLETED)))
                .doesNotThrowAnyException();
    }
}
