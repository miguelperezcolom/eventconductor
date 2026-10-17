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

    private ScheduleMcpTools guardedTools(io.mateu.workflow.security.AuthorizationContext caller) {
        when(definitions.findAll()).thenReturn(List.of(new WorkflowDefinition("payout", "Payout", 1, "", false, 0, false,
                null, 0, List.of(), false, WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE, List.of("payments:write"),
                List.of(), 0, null).withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null)))));
        var service = new ScheduleService(definitions, new InMemoryCronScheduleRepository(),
                new InMemorySchedulePauseRepository(), started::add).withFlowAuthorization(true);
        var guarded = new ScheduleMcpTools(service, definitions);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        if (caller != null) {
            beans.addBean("caller", (io.mateu.workflow.security.CallerResolver) () -> caller);
        }
        guarded.callerResolver = beans.getBeanProvider(io.mateu.workflow.security.CallerResolver.class);
        return guarded;
    }

    /** With flow authorization on, the caller the MCP door resolves must be allowed to start the workflow. */
    @Test
    void aCallerWhoMayNotStartTheWorkflowIsRefusedOnCreateEditAndRunNow() {
        var tools = guardedTools(new io.mateu.workflow.security.AuthorizationContext("bob", List.of(), List.of()));

        assertThatThrownBy(() -> tools.createSchedule("p", "payout", "0 0 9 * * *", null, null))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class)
                .hasMessageContaining("payments:write");
        assertThatThrownBy(() -> tools.runScheduleNow("eom"))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class);
        assertThat(tools.listSchedules(null)).extracting(ScheduleService.ScheduleView::id).containsExactly("eom");
        // Operating is not starting: pause and resume are not judged.
        assertThat(tools.pauseSchedule("eom").paused()).isTrue();
        assertThat(started).isEmpty();
    }

    /** No resolver at all is no caller, and no caller holds no scope: refused, not waved through. */
    @Test
    void withoutAResolvedCallerAGuardedWorkflowIsRefused() {
        var tools = guardedTools(null);

        assertThatThrownBy(() -> tools.runScheduleNow("eom"))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class);
        assertThat(started).isEmpty();
    }

    @Test
    void aCallerHoldingTheScopeCreatesEditsAndRunsTheSchedule() {
        var tools = guardedTools(new io.mateu.workflow.security.AuthorizationContext("alice",
                List.of("payments:write"), List.of()));

        assertThat(tools.createSchedule("p", "payout", "0 0 9 * * *", null, null).id()).isEqualTo("p");
        assertThat(tools.updateSchedule("p", "payout", "@daily", null, null).cron()).isEqualTo("@daily");
        assertThat(tools.runScheduleNow("p").workflowId()).isEqualTo("payout");
        // The start itself is the engine's, as an occurrence's would be.
        assertThat(started).singleElement().satisfies(start ->
                assertThat(io.mateu.workflow.security.AuthorizationContext.isSystem(start.caller())).isTrue());
    }

    @Test
    void anUnknownScheduleIsRefusedByTheTools() {
        assertThatThrownBy(() -> tools.getSchedule("ghost"))
                .isInstanceOf(ScheduleService.UnknownScheduleException.class);
        assertThatThrownBy(() -> tools.createSchedule("x", "ghost", "nope", null, null))
                .isInstanceOf(ScheduleService.InvalidScheduleException.class)
                .hasMessageContaining("workflow 'ghost' is unknown");
        assertThat(tools.getSystemContext()).contains("createSchedule", "exportScheduleYaml");
    }
}
