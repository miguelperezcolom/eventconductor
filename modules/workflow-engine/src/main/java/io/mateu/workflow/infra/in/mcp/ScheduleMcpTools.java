package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.application.services.ScheduleService.RunNowResult;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The schedules as MCP tools: consult and operate any of them, create and edit the ones created
 * through the engine, and hand one over to git. Collected with the other {@link McpTools} beans.
 *
 * <p>Every tool returns a concrete type: Spring AI silently drops a tool returning {@code Object}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleMcpTools implements McpTools, McpSystemContext {

    private static final String CALLER = "mcp";

    private final ScheduleService scheduleService;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;

    /**
     * Who is calling, for the flow-authorization check on create/edit/run-now. Field-injected and
     * optional: an MCP endpoint behind Spring Security resolves its caller like any other door.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.security.CallerResolver> callerResolver;

    private io.mateu.workflow.security.AuthorizationContext callerContext() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    @Override
    public String getSystemContext() {
        return """
                Programaciones (schedules) del motor de workflows:
                - Cada programación arranca un workflow en cada ocurrencia de su cron (Spring, seis campos con segundos: "0 0 9 * * MON-FRI"), en su zona horaria, con sus variables iniciales. Se identifican por id (una programación por id).
                - Origen: "Definition" (declarada en la definición), "cron.yaml (git|directory|classpath)" (en un cron.yaml junto a las definiciones) o "UI/API" (creada desde la consola, la API REST o estas herramientas).
                - Solo las de origen UI/API se crean, editan y borran aquí (createSchedule, updateSchedule, deleteSchedule). Las declaradas en ficheros pertenecen al fichero: se cambian en git; aquí solo se pausan (pauseSchedule), reanudan (resumeSchedule) o se lanzan ya (runScheduleNow).
                - Flujo recomendado: crear y probar aquí, después exportScheduleYaml y commitear la entrada en el cron.yaml del repositorio; al importarse, el fichero pasa a ser la fuente de verdad de ese id (sustituye a la de UI/API, conserva la pausa) y, si desaparece del fichero, se borra.
                - listWorkflowDefinitions da los workflows disponibles para programar.
                """;
    }

    public record WorkflowDefinitionSummary(String id, String name, String status, boolean acceptsNewInstances,
                                            String description, java.util.List<String> tags) {}

    @Tool(description = "List every schedule (one per id) with its workflow, cron, zone, variables, source (Definition,"
            + " cron.yaml (git|directory|classpath) or UI/API), whether it is editable here, whether it is paused, its"
            + " state and next starts. Only UI/API schedules are editable; file-owned ones (Definition, cron.yaml) can"
            + " only be paused, resumed or run now. Optionally only the schedules of one workflow.")
    public List<ScheduleView> listSchedules(
            @ToolParam(required = false, description = "Only the schedules of this workflow definition id; empty for all")
            String workflowId) {
        log.info("Listing schedules, workflow={}", workflowId);
        return scheduleService.list(workflowId, null);
    }

    @Tool(description = "Get one schedule by id: workflow, cron (and in words), zone, variables, source, editable,"
            + " paused (when and by whom), state and its next five starts.")
    public ScheduleView getSchedule(String scheduleId) {
        return scheduleService.get(scheduleId);
    }

    @Tool(description = "Create a schedule (source UI/API) that starts a workflow at every occurrence of a cron."
            + " id: a free name such as nightly-report (letters, digits, '.', '_', '-'); workflowId: an existing workflow"
            + " that accepts instances (see listWorkflowDefinitions); cron: Spring cron with six fields, seconds first"
            + " (\"0 0 9 * * MON-FRI\") or a macro (@daily); zone: IANA zone such as Europe/Madrid, empty for the engine's"
            + " default; variables: the initial process variables. Returns the schedule with its next starts.")
    public ScheduleView createSchedule(String id, String workflowId, String cron,
                                       @ToolParam(required = false, description = "IANA zone; empty for the default") String zone,
                                       @ToolParam(required = false, description = "Initial process variables by name") Map<String, String> variables) {
        log.info("Creating schedule {} for {}", id, workflowId);
        return scheduleService.create(new ScheduleDraft(id, workflowId, cron, zone, variables), callerContext());
    }

    @Tool(description = "Edit a UI/API schedule: its workflow, cron, zone and variables (all replaced; the id never"
            + " changes). Fails for a schedule declared in a definition or a cron.yaml: those are edited in their file.")
    public ScheduleView updateSchedule(String scheduleId, String workflowId, String cron,
                                       @ToolParam(required = false, description = "IANA zone; empty for the default") String zone,
                                       @ToolParam(required = false, description = "Initial process variables by name") Map<String, String> variables) {
        log.info("Updating schedule {}", scheduleId);
        return scheduleService.update(scheduleId, new ScheduleDraft(scheduleId, workflowId, cron, zone, variables), callerContext());
    }

    @Tool(description = "Delete a UI/API schedule (and its pause). Fails for a schedule declared in a definition or a"
            + " cron.yaml: remove it from its file instead.")
    public String deleteSchedule(String scheduleId) {
        log.info("Deleting schedule {}", scheduleId);
        scheduleService.delete(scheduleId);
        return "Schedule " + scheduleId + " deleted";
    }

    @Tool(description = "Pause any schedule (file-owned or UI/API): it does not start its workflow until resumed."
            + " The pause is kept by id, so it survives re-imports of the file that declares it.")
    public ScheduleView pauseSchedule(String scheduleId) {
        log.info("Pausing schedule {}", scheduleId);
        return scheduleService.pause(scheduleId, CALLER);
    }

    @Tool(description = "Resume a paused schedule. Occurrences missed while paused are not replayed: it fires again"
            + " from its next occurrence.")
    public ScheduleView resumeSchedule(String scheduleId) {
        log.info("Resuming schedule {}", scheduleId);
        return scheduleService.resume(scheduleId);
    }

    @Tool(description = "Start the schedule's workflow once, now, with the schedule's variables — even if the schedule"
            + " is paused. Returns the business key of the process it starts (<workflow>-cron-manual-<id>-<instant>).")
    public RunNowResult runScheduleNow(String scheduleId) {
        log.info("Running schedule {} now", scheduleId);
        return scheduleService.runNow(scheduleId, CALLER, callerContext());
    }

    @Tool(description = "Promote a schedule to git: returns its cron.yaml entry (id included), ready to paste into the"
            + " cron.yaml next to the workflow definitions. Once that file is imported, the file owns the schedule: it"
            + " replaces the UI/API one with the same id (keeping its pause), and removing it from the file deletes it.")
    public String exportScheduleYaml(String scheduleId) {
        return scheduleService.exportYaml(scheduleId);
    }

    @Tool(description = "List the workflow definitions (id, name, status, whether it accepts new instances,"
            + " description) — the workflows a schedule can start.")
    public List<WorkflowDefinitionSummary> listWorkflowDefinitions() {
        return workflowDefinitionRepository.findAll().stream()
                .filter(d -> d.id() != null)
                .sorted(Comparator.comparing(d -> d.id()))
                .map(d -> new WorkflowDefinitionSummary(d.id(), d.name(), d.status().name(),
                        d.status().accceptsNewInstances(), d.description(), d.tagsOrEmpty()))
                .toList();
    }
}
