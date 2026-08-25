package io.mateu.workflow.infra.in.ui.pages.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.CommandDispatcher;
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

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                se("start", StepExecutionStatus.COMPLETED),
                se("charge", StepExecutionStatus.RUNNING),
                se("ship", StepExecutionStatus.PENDING)), List.of());

        assertThat(element).isNotNull();
        assertThat(element.name()).isEqualTo("eventconductor-workflow-graph");
        assertThat(element.attributes().get("readonly")).isEqualTo("true");
        JsonNode overlay = mapper.readTree(view.processGraphOverlay);
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

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                se("charge", StepExecutionStatus.COMPLETED),
                se("charge", StepExecutionStatus.RUNNING)), List.of()); // a retry is still running

        JsonNode overlay = mapper.readTree(view.processGraphOverlay);
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
        var publisher = mock(CommandDispatcher.class);
        var view = new SimpleProcessViewModel(
                publisher, null, null, null, null, null, null, null, null, null);
        view.id = "p-1";

        view.retryProcess();

        verify(publisher).dispatch(new RetryProcessRequested("p-1"));
    }

    @Test
    void restartPublishesARestartRequestForThisProcessOnTheOwningPod() {
        var publisher = mock(CommandDispatcher.class);
        var view = new SimpleProcessViewModel(
                publisher, null, null, null, null, null, null, null, null, null);
        view.id = "p-1";

        view.restartProcess();

        verify(publisher).dispatch(new RestartProcessRequested("p-1"));
    }

    // --- runtime-injected steps in the diagram --------------------------------------------------

    private io.mateu.workflow.domain.aggregates.Step stepDef(String id, io.mateu.workflow.domain.aggregates.StepType type, String preconditionStepId) {
        return new io.mateu.workflow.domain.aggregates.Step(id, "wd-1", type, id, null, preconditionStepId,
                null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    /** A real (non-mock) execution carrying its own stepJson and, optionally, the injected marker. */
    private StepExecution exec(String stepId, StepExecutionStatus status, int order, String injectedBy) {
        return StepExecution.builder()
                .id("se-" + stepId).processId("p-1").workflowDefinitionId("wd-1")
                .stepId(stepId).stepJson(io.mateu.core.infra.JsonSerializer.toJson(stepDef(stepId,
                        io.mateu.workflow.domain.aggregates.StepType.ACTION, "plan")))
                .status(status).order(order).variables(List.of())
                .injectedByStepExecutionId(injectedBy).build();
    }

    @Test
    void graphValueIncludesRuntimeInjectedStepsNotInTheDefinition() throws Exception {
        // Definition declares only start -> plan(DYNAMIC); 'task-a' was injected at runtime.
        var def = new WorkflowDefinition("wd-1", "P", 1, null, false, 0, false, null, 0, List.of(
                stepDef("start", io.mateu.workflow.domain.aggregates.StepType.START, null),
                stepDef("plan", io.mateu.workflow.domain.aggregates.StepType.DYNAMIC, "start")));
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(def));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                exec("start", StepExecutionStatus.COMPLETED, 1, null),
                exec("plan", StepExecutionStatus.COMPLETED, 2, null),
                exec("task-a", StepExecutionStatus.RUNNING, 3, "se-plan")), List.of());

        // The graph value carries the injected step, with its real precondition — a value built from
        // the definition alone would omit it entirely.
        JsonNode value = mapper.readTree(view.processGraph);
        var stepIds = new java.util.ArrayList<String>();
        value.get("steps").forEach(s -> stepIds.add(s.get("id").asText()));
        assertThat(stepIds).containsExactly("start", "plan", "task-a");
        var injectedStep = java.util.stream.StreamSupport.stream(value.get("steps").spliterator(), false)
                .filter(s -> "task-a".equals(s.get("id").asText())).findFirst().orElseThrow();
        assertThat(injectedStep.get("preconditionStepId").asText()).isEqualTo("plan");

        // The overlay flags the injected step (frontend badge data), and not the declared ones.
        JsonNode overlay = mapper.readTree(view.processGraphOverlay);
        assertThat(overlay.get("task-a").get("injected").asBoolean()).isTrue();
        assertThat(overlay.get("task-a").get("injectedBy").asText()).isEqualTo("se-plan");
        assertThat(overlay.get("plan").has("injected")).isFalse();
    }

    @Test
    void graphValueIsTheDefinitionUntouchedWhenNothingWasInjected() throws Exception {
        var def = new WorkflowDefinition("wd-1", "P", 1, null, false, 0, false, null, 0, List.of(
                stepDef("start", io.mateu.workflow.domain.aggregates.StepType.START, null),
                stepDef("plan", io.mateu.workflow.domain.aggregates.StepType.DYNAMIC, "start")));
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(def));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                exec("start", StepExecutionStatus.COMPLETED, 1, null),
                exec("plan", StepExecutionStatus.RUNNING, 2, null)), List.of());

        JsonNode value = mapper.readTree(view.processGraph);
        var stepIds = new java.util.ArrayList<String>();
        value.get("steps").forEach(s -> stepIds.add(s.get("id").asText()));
        assertThat(stepIds).containsExactly("start", "plan");
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

    private StepExecution startedAt(String stepId, StepExecutionStatus status, String when) {
        var se = se(stepId, status);
        when(se.getStartedAt()).thenReturn(java.time.LocalDateTime.parse(when));
        return se;
    }

    @Test
    void numbersTheStepsInTheOrderTheyStarted() throws Exception {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(emptyDefinition()));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                // Deliberately out of order, and the middle one finishes last: the number says when
                // a step took its turn, so it follows startedAt and not the order they arrive in.
                startedAt("ship", StepExecutionStatus.COMPLETED, "2026-08-19T10:00:30"),
                startedAt("start", StepExecutionStatus.COMPLETED, "2026-08-19T10:00:00"),
                startedAt("charge", StepExecutionStatus.RUNNING, "2026-08-19T10:00:10")), List.of());

        JsonNode overlay = mapper.readTree(view.processGraphOverlay);
        assertThat(overlay.get("start").get("order").asInt()).isEqualTo(1);
        assertThat(overlay.get("charge").get("order").asInt()).isEqualTo(2);
        assertThat(overlay.get("ship").get("order").asInt()).isEqualTo(3);
    }

    @Test
    void aStepThatNeverStartedGetsNoNumber() throws Exception {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(emptyDefinition()));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                startedAt("start", StepExecutionStatus.COMPLETED, "2026-08-19T10:00:00"),
                se("ship", StepExecutionStatus.PENDING)), List.of());

        JsonNode overlay = mapper.readTree(view.processGraphOverlay);
        assertThat(overlay.get("start").get("order").asInt()).isEqualTo(1);
        // An unnumbered node is one that has not had its turn — as much part of the reading as the
        // numbers are. A 0 or a 2 here would both be lies.
        assertThat(overlay.get("ship").has("order")).isFalse();
    }

    @Test
    void stepsThatStartTogetherAreNumberedTheSameWayOnEveryPoll() {
        var together = List.of(
                startedAt("b-branch", StepExecutionStatus.COMPLETED, "2026-08-19T10:00:05"),
                startedAt("a-branch", StepExecutionStatus.COMPLETED, "2026-08-19T10:00:05"));

        // Parallel branches routinely start in the same instant. Any order will do; the same one
        // every two seconds will not do itself — a number that changes under the reader is worse
        // than an arbitrary one.
        assertThat(SimpleProcessViewModel.executionOrder(together))
                .containsEntry("a-branch", 1)
                .containsEntry("b-branch", 2);
        assertThat(SimpleProcessViewModel.executionOrder(together.reversed()))
                .containsEntry("a-branch", 1)
                .containsEntry("b-branch", 2);
    }

    /**
     * The attributes say where to read, and the fields hold what is read.
     *
     * <p>That split is the whole of why the diagram follows the process now, and it is worth a test
     * of its own because both halves fail silently: an attribute written as a literal renders
     * perfectly and then never changes, and a payload put somewhere that is not a data field never
     * arrives. Neither shows up as an error anywhere.
     */
    @Test
    void theGraphTravelsAsStateAndTheAttributesOnlyPointAtIt() throws Exception {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.of(emptyDefinition()));
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        var view = view(defs);
        var element = view.buildDiagram(process, List.of(
                se("start", StepExecutionStatus.COMPLETED)), List.of());

        assertThat(element.attributes().get("value")).isEqualTo("${state.processGraph}");
        assertThat(element.attributes().get("overlay")).isEqualTo("${state.processGraphOverlay}");
        // And what those point at is really there, as text a State update can carry.
        assertThat(view.processGraph).contains("\"id\"");
        assertThat(view.processGraphOverlay).contains("COMPLETED");
    }
}
