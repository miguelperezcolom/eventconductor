package io.mateu.workflow.infra.in.ui.pages.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        return new WorkflowDefinition("wd-1", "P", 1, null,
                WorkflowDefinitionStatus.DRAFT, null, false, 0, false, null, 0, List.of());
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
                se("ship", StepExecutionStatus.PENDING)));

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
                se("charge", StepExecutionStatus.RUNNING))); // a retry is still running

        JsonNode overlay = mapper.readTree(element.attributes().get("overlay"));
        assertThat(overlay.get("charge").get("state").asText()).isEqualTo("RUNNING");
    }

    @Test
    void returnsNullWhenTheDefinitionIsMissing() {
        var defs = mock(WorkflowDefinitionRepository.class);
        when(defs.findById("wd-1")).thenReturn(Optional.empty());
        var process = mock(Process.class);
        when(process.getWorkflowDefinitionId()).thenReturn("wd-1");

        assertThat(view(defs).buildDiagram(process, List.of())).isNull();
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
    void ranksRunningAndErrorAboveCompleted() {
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.ERROR))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.RUNNING));
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.RUNNING))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.COMPLETED));
        assertThat(SimpleProcessViewModel.statusRank(StepExecutionStatus.COMPLETED))
                .isGreaterThan(SimpleProcessViewModel.statusRank(StepExecutionStatus.CANCELLED));
    }
}
