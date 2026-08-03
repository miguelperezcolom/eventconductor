package io.mateu.workflow.infra.in.ui.pages.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleProcessViewModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Only the workflow-definition repository is exercised by buildDiagram; the rest go unused. */
    private SimpleProcessViewModel view(WorkflowDefinitionRepository defs) {
        return new SimpleProcessViewModel(null, null, null, defs, null, null, null, null, null, null);
    }

    private StepExecution se(String stepId, StepExecutionStatus status) {
        var se = mock(StepExecution.class);
        when(se.getStepId()).thenReturn(stepId);
        when(se.getStatus()).thenReturn(status);
        return se;
    }

    private WorkflowDefinition emptyDefinition() {
        return new WorkflowDefinition("wd-1", "P", 1, null,  false, 0, false, null, 0, List.of());
    }

    @Test
    void buildsOverlayWithStatePerStepAndActiveOnRunning() throws Exception {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(emptyDefinition()));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var element = view(defs).buildDiagram(process, List.of(
                se("start", StepExecutionStatus.COMPLETED),
                se("charge", StepExecutionStatus.RUNNING),
                se("ship", StepExecutionStatus.PENDING)), List.of());

        assertThat(element).isNotNull();
        assertThat(element.name()).isEqualTo("eventconductor-workflow-graph");
        assertThat(element.attributes().get("readonly")).isEqualTo("true");
        JsonNode overlay = mapper.readTree(element.attributes().get("overlay"));
        assertThat(overlay.get("start").get("state").asText()).isEqualTo("COMPLETED");
        assertThat(overlay.get("start").has("active")).isFalse();
        assertThat(overlay.get("charge").get("state").asText()).isEqualTo("RUNNING");
        assertThat(overlay.get("charge").get("active").asBoolean()).isTrue();
        assertThat(overlay.get("ship").get("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void collapsesRetriesKeepingTheMostTellingStatus() throws Exception {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(emptyDefinition()));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var element = view(defs).buildDiagram(process, List.of(
                se("charge", StepExecutionStatus.COMPLETED),
                se("charge", StepExecutionStatus.RUNNING)), List.of()); // a retry is still running

        JsonNode overlay = mapper.readTree(element.attributes().get("overlay"));
        assertThat(overlay.get("charge").get("state").asText()).isEqualTo("RUNNING");
    }

    @Test
    void returnsNullWhenTheDefinitionIsMissing() {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.empty());
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        assertThat(view(defs).buildDiagram(process, List.of(), List.of())).isNull();
    }

    @Test
    void mapsEveryExecutionStatusToAnOverlayToken() {
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.RUNNING)).isEqualTo("RUNNING");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.COMPLETED)).isEqualTo("COMPLETED");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.ERROR)).isEqualTo("ERROR");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.TIMEOUT)).isEqualTo("ERROR");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.CANCELLED)).isEqualTo("CANCELLED");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.CREATED)).isEqualTo("PENDING");
        assertThat(SimpleProcessViewModel.overlayState(StepExecutionStatus.PENDING)).isEqualTo("PENDING");
    }

    @Test
    void overlayEntryConsolidatesTheReasonForAMessageWait() {
        var se = mock(StepExecution.class);
        when(se.getStatus()).thenReturn(StepExecutionStatus.PENDING);
        when(se.getAwaitingMessageName()).thenReturn("PaymentConfirmed");
        when(se.getAwaitingCorrelationKey()).thenReturn("order-42");

        var entry = SimpleProcessViewModel.overlayEntry(se, null);

        assertThat(entry.get("state")).isEqualTo("PENDING");
        assertThat(entry.get("reason"))
                .isEqualTo("Waiting for message 'PaymentConfirmed' with key 'order-42'");
        assertThat(entry.get("awaitingMessage")).isEqualTo("PaymentConfirmed");
        assertThat(entry.get("correlationKey")).isEqualTo("order-42");
    }

    @Test
    void overlayEntrySurfacesTheStepErrorAndRetryCount() {
        var se = mock(StepExecution.class);
        when(se.getStatus()).thenReturn(StepExecutionStatus.ERROR);
        when(se.getAttemptCount()).thenReturn(2);

        var entry = SimpleProcessViewModel.overlayEntry(se, "payment declined");

        assertThat(entry.get("reason")).isEqualTo("Failed on attempt 3: payment declined");
        assertThat(entry.get("error")).isEqualTo("payment declined");
        assertThat(entry.get("attempt")).isEqualTo(2);
    }

    @Test
    void keepsTheNewestErrorPerStepExecutionAndIgnoresNonErrors() {
        var older = LogMessage.builder().stepExecutionId("x").messageType("error")
                .message("first").timestamp(LocalDateTime.parse("2026-08-02T10:00")).build();
        var newer = LogMessage.builder().stepExecutionId("x").messageType("error")
                .message("second").timestamp(LocalDateTime.parse("2026-08-02T11:00")).build();
        var noise = LogMessage.builder().stepExecutionId("x").messageType("info")
                .message("noise").timestamp(LocalDateTime.parse("2026-08-02T12:00")).build();

        var latest = SimpleProcessViewModel.latestErrorByStepExecution(List.of(older, newer, noise));

        assertThat(latest.get("x")).isEqualTo("second");
    }

    /**
     * Both ways of running a process again are offered for a process that stopped — failed or
     * cancelled — and for nothing else. A cancelled process is not finished, and picking it up is
     * a normal operator move; a running one is not the operator's to re-drive, and a completed one
     * is done.
     */
    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class, names = {"ERROR", "CANCELLED"})
    void offersBothWaysToRunAStoppedProcessAgain(ProcessStatus stopped) {
        var view = view(null);
        view.processStatus = stopped;

        assertThat(view.isHidden("retryProcess", null)).isFalse();
        assertThat(view.isHidden("restartProcess", null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class,
            names = {"PENDING", "RUNNING", "PAUSED", "COMPLETED", "COMPENSATED"})
    void offersNeitherWhileTheProcessIsLiveOrFinished(ProcessStatus notStopped) {
        var view = view(null);
        view.processStatus = notStopped;

        assertThat(view.isHidden("retryProcess", null)).isTrue();
        assertThat(view.isHidden("restartProcess", null)).isTrue();
    }

    @Test
    void retryPublishesARetryRequestForThisProcessOnTheOwningPod() {
        var publisher = mock(UpstreamEventPublisher.class);
        var view = new SimpleProcessViewModel(
                publisher, null, null, null, null, null, null, null, null, null);
        view.id = "p-1";

        view.retryProcess();

        verify(publisher).publish(new RetryProcessRequested("p-1"));
    }

    @Test
    void restartPublishesARestartRequestForThisProcessOnTheOwningPod() {
        var publisher = mock(UpstreamEventPublisher.class);
        var view = new SimpleProcessViewModel(
                publisher, null, null, null, null, null, null, null, null, null);
        view.id = "p-1";

        view.restartProcess();

        verify(publisher).publish(new RestartProcessRequested("p-1"));
    }

    @Test
    void ranksRunningAndErrorAboveCompleted() {
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.ERROR))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.RUNNING));
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.RUNNING))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.COMPLETED));
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.COMPLETED))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.CANCELLED));
    }
}
