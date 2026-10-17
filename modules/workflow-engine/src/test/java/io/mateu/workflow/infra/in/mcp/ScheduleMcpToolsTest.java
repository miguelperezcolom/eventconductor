package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The schedule tools: their published names (pinned, as {@code WorkflowMcpToolSurfaceTest} pins the
 * workflow tools — renaming one breaks every agent configured with it), that Spring AI actually
 * registers every one of them (a tool it cannot describe is dropped without a word), and what they do.
 */
class ScheduleMcpToolsTest {

    private static final String[] PUBLISHED_TOOLS = {
            "listSchedules",
            "getSchedule",
            "createSchedule",
            "updateSchedule",
            "deleteSchedule",
            "pauseSchedule",
            "resumeSchedule",
            "runScheduleNow",
            "exportScheduleYaml",
            "listWorkflowDefinitions",
    };

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final List<ProcessCreationRequested> started = new ArrayList<>();
    ScheduleMcpTools tools;

    @BeforeEach
    void setUp() {
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("report", "Report", 1, "Daily report", false, 0, false, null, 0, List.of())
                        .withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null))),
                new WorkflowDefinition("old", "Old", 1, null, false, 0, false, null, 0, List.of())
                        .withRuntimeStatus(WorkflowStatus.DISABLED)));
        var service = new ScheduleService(definitions, new InMemoryCronScheduleRepository(),
                new InMemorySchedulePauseRepository(), started::add)
                .withClock(() -> Instant.parse("2026-10-14T10:00:00Z"));
        tools = new ScheduleMcpTools(service, definitions);
    }

    @Test
    void thePublishedToolsAreExactlyTheOnesTheContractNames() {
        assertThat(Arrays.stream(ScheduleMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName))
                .containsExactlyInAnyOrder(PUBLISHED_TOOLS);
    }

    @Test
    void springAiRegistersEveryTool() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder(PUBLISHED_TOOLS);
        assertThat(Arrays.stream(callbacks)).allSatisfy(c -> assertThat(c.getToolDefinition().description()).isNotBlank());
    }

    @Test
    void createsOperatesAndPromotesASchedule() {
        var created = tools.createSchedule("nightly", "report", "0 0 9 * * *", "UTC", Map.of("region", "EMEA"));
        assertThat(created.sourceLabel()).isEqualTo("UI/API");

        assertThat(tools.pauseSchedule("nightly").pausedBy()).isEqualTo("mcp");
        assertThat(tools.resumeSchedule("nightly").paused()).isFalse();
        assertThat(tools.updateSchedule("nightly", "report", "@hourly", null, null).cron()).isEqualTo("@hourly");
        assertThat(tools.runScheduleNow("nightly").businessKey()).startsWith("report-cron-manual-nightly-");
        assertThat(started).hasSize(1);
        assertThat(tools.exportScheduleYaml("nightly")).contains("- id: nightly");
        assertThat(tools.listSchedules("report")).extracting(ScheduleService.ScheduleView::id)
                .containsExactly("eom", "nightly");

        assertThat(tools.deleteSchedule("nightly")).contains("deleted");
        assertThat(tools.listSchedules(null)).extracting(ScheduleService.ScheduleView::id).containsExactly("eom");
    }

    @Test
    void aFileOwnedScheduleIsNotEditedThroughTheTools() {
        assertThatThrownBy(() -> tools.updateSchedule("eom", "report", "@daily", null, null))
                .isInstanceOf(ScheduleService.ScheduleNotEditableException.class);
        assertThatThrownBy(() -> tools.deleteSchedule("eom"))
                .isInstanceOf(ScheduleService.ScheduleNotEditableException.class);
        assertThat(tools.pauseSchedule("eom").paused()).isTrue();
    }

    @Test
    void listsTheWorkflowsASchedulesCanStart() {
        assertThat(tools.listWorkflowDefinitions()).containsExactly(
                new ScheduleMcpTools.WorkflowDefinitionSummary("old", "Old", "DISABLED", false, null, java.util.List.of()),
                new ScheduleMcpTools.WorkflowDefinitionSummary("report", "Report", "ACTIVE", true, "Daily report", java.util.List.of()));
    }
}
