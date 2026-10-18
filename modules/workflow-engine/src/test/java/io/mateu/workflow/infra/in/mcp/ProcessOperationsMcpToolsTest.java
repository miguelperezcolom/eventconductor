package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.IncidentRepository.FailedStep;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessOperationsMcpToolsTest {

    final ProcessOperationsService operations = mock(ProcessOperationsService.class);
    final IncidentService incidents = mock(IncidentService.class);
    final ProcessOperationsMcpTools tools = new ProcessOperationsMcpTools(operations, incidents);

    @Test
    void theToolsCallTheSameServicesAndSayWhenTheyAreRefused() {
        assertThat(tools.setProcessVariables("p-1", Map.of("a", "1"), List.of("b"), "why")).contains("requested");
        verify(operations).setVariables(eq("p-1"), eq(List.of(new Variable("a", "1"))), eq(List.of("b")), eq("why"), any());

        assertThat(tools.completeStepManually("p-1", "s-1", null, "why")).contains("s-1");
        verify(operations).completeStep(eq("p-1"), eq("s-1"), eq(List.of()), eq("why"), any());

        doThrow(new ProcessOperationsService.ProcessStateConflictException("p-1", "retry", null, "nope"))
                .when(operations).retryFromStep(any(), any(), any(), any());
        assertThat(tools.retryProcessFromStep("p-1", "a", "why")).startsWith("Refused:").contains("nope");
    }

    @Test
    void incidentsAreListedDrilledIntoAndRetried() {
        var group = new IncidentService.IncidentGroup("k1", "orders", "charge", "Card # declined", "Card 1 declined",
                2, 2, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 1));
        when(incidents.incidents(null)).thenReturn(new IncidentService.Incidents(List.of(group), 2, false));
        when(incidents.members("k1", 100)).thenReturn(List.of(new FailedStep("p-1", "bk", "orders", "charge", "s-1",
                StepExecutionStatus.ERROR, LocalDateTime.of(2026, 1, 1, 0, 0), "Card 1 declined")));
        when(incidents.retryGroup(eq("k1"), eq("fixed"), any())).thenReturn(new IncidentService.RetryOutcome("k1", 2, true, false));

        assertThat(tools.listIncidents(null)).singleElement().satisfies(entry -> {
            assertThat(entry.key()).isEqualTo("k1");
            assertThat(entry.failedSteps()).isEqualTo(2);
        });
        assertThat(tools.getIncidentProcesses("k1")).singleElement().satisfies(member ->
                assertThat(member.processId()).isEqualTo("p-1"));
        assertThat(tools.retryIncident("k1", "fixed")).contains("2 step retries").contains("background");
        assertThat(tools.getSystemContext()).contains("retryProcessFromStep");
    }
}
