package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ResumeWorkflowUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowMcpTools implements McpTools, McpSystemContext {

    @Override
    public String getSystemContext() {
        return """
                Motor de orquestación de procesos (Sagas/Workflows):
                - Puedes listar, consultar y diagnosticar procesos de negocio.
                - Puedes consultar variables de proceso, pasos de ejecución y logs.
                - Puedes reintentar los pasos fallidos de un proceso en estado ERROR.
                - Puedes pausar (pauseProcess) y reanudar (resumeProcess) procesos: en pausa no arrancan pasos nuevos y los relojes de timers/timeouts quedan congelados; al reanudar se desplazan por la duración de la pausa.
                - Puedes pausar (pauseWorkflow) y reanudar (resumeWorkflow) una definición completa: pausa todos sus procesos y las instancias nuevas nacen pausadas.
                - Puedes enviar mensajes externos (sendMessage) para reanudar procesos que esperan en un paso MESSAGE, correlacionando por businessKey o por la correlationExpression del paso.
                - Puedes consultar analíticas de procesos por definición (getWorkflowAnalytics): recuentos por estado, tasas de finalización/error, throughput diario y duraciones media/p95 por proceso y por paso.
                - Puedes localizar dónde se atascan los procesos (findBottleneck): paso más lento, pasos con instancias esperando y pasos con fallos.
                Estados posibles de un proceso: PENDING, RUNNING, PAUSED, COMPLETED, CANCELLED, ERROR.
                Cada proceso tiene un ID único, un businessKey (clave de negocio) y un porcentaje de completado.
                Los pasos (StepExecution) contienen el workerId que los ejecutó y su estado individual.
                """;
    }


    private final ProcessRepository processRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final LogMessageRepository logMessageRepository;
    private final RetryProcessUseCase retryProcessUseCase;
    private final PauseProcessUseCase pauseProcessUseCase;
    private final ResumeProcessUseCase resumeProcessUseCase;
    private final PauseWorkflowUseCase pauseWorkflowUseCase;
    private final ResumeWorkflowUseCase resumeWorkflowUseCase;
    private final ImportWorkflowDefinitionsFromGitUseCase importWorkflowDefinitionsFromGitUseCase;
    private final UpstreamEventPublisher upstreamEventPublisher;
    private final ProcessAnalyticsService processAnalyticsService;

    public record ProcessSummary(
            String id, String name, String businessKey,
            String status, int completionPct, String created, String finished) {}

    public record ProcessDetail(
            String id, String name, String businessKey, String status,
            int completionPct, List<String> variables, List<StepSummary> steps) {}

    public record StepSummary(
            String id, String stepId, String status, String workerId, String startedAt) {}

    public record LogEntry(
            String timestamp, String stepExecutionId, String type, String message, String workerId) {}

    @Tool(description = "List all workflow processes with their ID, name, business key, status and completion percentage")
    public List<ProcessSummary> listProcesses() {
        log.info("Listing processes");
        return processRepository.findAll().stream()
                .map(p -> new ProcessSummary(
                        p.getId(), p.getName(), p.getBusinessKey(),
                        p.getStatus().name(), p.getCompletionPercentage(),
                        p.getCreated() != null ? p.getCreated().toString() : null,
                        p.getFinished() != null ? p.getFinished().toString() : null))
                .toList();
    }

    @Tool(description = "Get full details of a workflow process including variables and step executions")
    public ProcessDetail getProcessDetails(String processId) {
        log.info("Getting process " + processId);
        Process process = processRepository.findById(processId)
                .orElseThrow(() -> new IllegalArgumentException("Process not found: " + processId));
        var steps = stepExecutionRepository.findByProcess(process).stream()
                .map(s -> new StepSummary(
                        s.getId(), s.getStepId(), s.getStatus().name(),
                        s.getWorkerId(),
                        s.getStartedAt() != null ? s.getStartedAt().toString() : null))
                .toList();
        var vars = process.getVariables() != null
                ? process.getVariables().stream().map(v -> v.name() + "=" + v.value()).toList()
                : List.<String>of();
        return new ProcessDetail(
                process.getId(), process.getName(), process.getBusinessKey(),
                process.getStatus().name(), process.getCompletionPercentage(), vars, steps);
    }

    @Tool(description = "Find a workflow process by its business key")
    public ProcessDetail findProcessByBusinessKey(String businessKey) {
        log.info("Finding process by business key " + businessKey);
        Process process = processRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new IllegalArgumentException("No process found for business key: " + businessKey));
        return getProcessDetails(process.getId());
    }

    @Tool(description = "Get log messages for a workflow process")
    public List<LogEntry> getProcessLogs(String processId) {
        log.info("Getting logs for process " + processId);
        return logMessageRepository.findAll().stream()
                .filter(log -> processId.equals(log.getProcessId()))
                .map(log -> new LogEntry(
                        log.getTimestamp() != null ? log.getTimestamp().toString() : null,
                        log.getStepExecutionId(), log.getMessageType(),
                        log.getMessage(), log.getWorkerId()))
                .toList();
    }

    @Tool(description = "Retry all failed (ERROR) step executions in a workflow process")
    public String retryProcess(String processId) {
        log.info("Retrying process " + processId);
        retryProcessUseCase.handle(new RetryProcessCommand(processId));
        return "Retry triggered for process " + processId;
    }

    @Tool(description = "Pause a PENDING or RUNNING workflow process: no new steps start and timer/timeout clocks freeze until it is resumed. In-flight work is not cancelled — worker reports and messages are still accepted, only successors are held.")
    public String pauseProcess(String processId) {
        log.info("Pausing process " + processId);
        pauseProcessUseCase.handle(new PauseProcessCommand(processId));
        return "Pause triggered for process " + processId;
    }

    @Tool(description = "Resume a PAUSED workflow process: timer/timeout clocks are shifted forward by the pause duration and the flow moves on.")
    public String resumeProcess(String processId) {
        log.info("Resuming process " + processId);
        resumeProcessUseCase.handle(new ResumeProcessCommand(processId));
        return "Resume triggered for process " + processId;
    }

    @Tool(description = "Pause a workflow definition: pauses all its PENDING/RUNNING processes, and new instances (cron included) are still created but born PAUSED until the definition is resumed.")
    public String pauseWorkflow(String workflowDefinitionId) {
        log.info("Pausing workflow definition " + workflowDefinitionId);
        pauseWorkflowUseCase.handle(workflowDefinitionId);
        return "Pause triggered for workflow definition " + workflowDefinitionId;
    }

    @Tool(description = "Resume a paused workflow definition: clears the pause flag and resumes all its PAUSED processes (including the ones born paused).")
    public String resumeWorkflow(String workflowDefinitionId) {
        log.info("Resuming workflow definition " + workflowDefinitionId);
        resumeWorkflowUseCase.handle(workflowDefinitionId);
        return "Resume triggered for workflow definition " + workflowDefinitionId;
    }

    @Tool(description = "Send an external message to running workflow processes. Processes waiting on a MESSAGE step with this message name resume when the correlation key matches (the process business key by default, or the value of the step's correlationExpression). The variables map is merged into the process variables. Messages that match no waiting step are ignored, not buffered.")
    public String sendMessage(String messageName, String correlationKey, java.util.Map<String, String> variables) {
        log.info("Sending message " + messageName + " with correlation key " + correlationKey);
        var messageVariables = variables == null ? List.<Variable>of() : variables.entrySet().stream()
                .map(entry -> new Variable(entry.getKey(), entry.getValue()))
                .toList();
        upstreamEventPublisher.publish(new MessageReceived(messageName, correlationKey, messageVariables));
        return "Message '" + messageName + "' sent with correlation key '" + correlationKey + "'";
    }

    public record StepStats(
            String stepId, long executions, long completed, long failed, long stuckPendingOrRunning,
            Double avgDurationSeconds, Double p95DurationSeconds, boolean bottleneck) {}

    public record WorkflowAnalytics(
            String workflowDefinitionId, String workflowDefinitionName, int windowDays,
            long totalInstances, Map<String, Long> instancesByStatus,
            double completionRatePct, double errorRatePct, double cancellationRatePct,
            Map<String, Long> processesCreatedPerDay,
            Double avgProcessDurationSeconds, Double p95ProcessDurationSeconds,
            String bottleneckStepId, List<StepStats> steps) {}

    public record BottleneckReport(
            String workflowDefinitionId, String workflowDefinitionName, int windowDays,
            String summary, List<StepStats> steps) {}

    @Tool(description = "Get process analytics for a workflow definition (by id or name): instance counts by status, completion/error/cancellation rates, processes created per day, average and p95 process duration, and per-step durations with the slowest step flagged as bottleneck. lastDays defaults to 30; pass 0 for all time. Leave workflowDefinitionIdOrName empty to get analytics for every definition.")
    public List<WorkflowAnalytics> getWorkflowAnalytics(String workflowDefinitionIdOrName, Integer lastDays) {
        log.info("Getting analytics for " + workflowDefinitionIdOrName + " over last " + lastDays + " days");
        var window = window(lastDays);
        if (workflowDefinitionIdOrName == null || workflowDefinitionIdOrName.isBlank()) {
            return processAnalyticsService.analyzeAll(window).stream()
                    .map(analytics -> toWorkflowAnalytics(analytics, windowDays(lastDays)))
                    .toList();
        }
        return processAnalyticsService.analyze(workflowDefinitionIdOrName, window)
                .map(analytics -> List.of(toWorkflowAnalytics(analytics, windowDays(lastDays))))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No workflow definition found for: " + workflowDefinitionIdOrName));
    }

    @Tool(description = "Find where processes of a workflow definition (by id or name) get stuck: the slowest step (bottleneck by average duration), steps with instances currently waiting or running, and steps with failures. lastDays defaults to 30; pass 0 for all time.")
    public BottleneckReport findBottleneck(String workflowDefinitionIdOrName, Integer lastDays) {
        log.info("Finding bottleneck for " + workflowDefinitionIdOrName + " over last " + lastDays + " days");
        var analytics = processAnalyticsService.analyze(workflowDefinitionIdOrName, window(lastDays))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No workflow definition found for: " + workflowDefinitionIdOrName));
        var steps = analytics.steps().stream().map(WorkflowMcpTools::toStepStats).toList();
        var summary = new StringBuilder();
        if (analytics.totalInstances() == 0) {
            summary.append("No process instances in the window.");
        } else if (analytics.bottleneckStepId() == null) {
            summary.append("No step has finished executions with measured durations yet.");
        } else {
            var bottleneck = steps.stream().filter(StepStats::bottleneck).findFirst().orElseThrow();
            summary.append("Slowest step (bottleneck): '").append(bottleneck.stepId())
                    .append("' with average ").append(bottleneck.avgDurationSeconds())
                    .append(" s and p95 ").append(bottleneck.p95DurationSeconds()).append(" s.");
        }
        steps.stream().filter(step -> step.stuckPendingOrRunning() > 0).forEach(step ->
                summary.append(" Step '").append(step.stepId()).append("' has ")
                        .append(step.stuckPendingOrRunning()).append(" instance(s) currently waiting or running."));
        steps.stream().filter(step -> step.failed() > 0).forEach(step ->
                summary.append(" Step '").append(step.stepId()).append("' has ")
                        .append(step.failed()).append(" failed execution(s)."));
        return new BottleneckReport(
                analytics.workflowDefinitionId(), analytics.workflowDefinitionName(),
                windowDays(lastDays), summary.toString(), steps);
    }

    private static ProcessAnalyticsService.TimeWindow window(Integer lastDays) {
        var days = windowDays(lastDays);
        return days == 0 ? ProcessAnalyticsService.TimeWindow.all()
                : ProcessAnalyticsService.TimeWindow.lastDays(days);
    }

    private static int windowDays(Integer lastDays) {
        return lastDays == null || lastDays < 0 ? 30 : lastDays;
    }

    private static WorkflowAnalytics toWorkflowAnalytics(ProcessAnalyticsService.DefinitionAnalytics analytics, int windowDays) {
        var byStatus = new java.util.LinkedHashMap<String, Long>();
        analytics.instancesByStatus().forEach((status, count) -> byStatus.put(status.name(), count));
        var perDay = new java.util.LinkedHashMap<String, Long>();
        analytics.createdPerDay().forEach((day, count) -> perDay.put(day.toString(), count));
        return new WorkflowAnalytics(
                analytics.workflowDefinitionId(), analytics.workflowDefinitionName(), windowDays,
                analytics.totalInstances(), byStatus,
                analytics.completionRatePct(), analytics.errorRatePct(), analytics.cancellationRatePct(),
                perDay,
                seconds(analytics.processDuration().average()), seconds(analytics.processDuration().p95()),
                analytics.bottleneckStepId(),
                analytics.steps().stream().map(WorkflowMcpTools::toStepStats).toList());
    }

    private static StepStats toStepStats(ProcessAnalyticsService.StepAnalytics step) {
        return new StepStats(
                step.stepId(), step.executions(), step.completed(), step.failed(), step.active(),
                seconds(step.duration().average()), seconds(step.duration().p95()), step.bottleneck());
    }

    private static Double seconds(java.time.Duration duration) {
        return duration == null ? null : Math.round(duration.toMillis() / 100d) / 10d;
    }

    @Tool(description = "Import workflow definitions from configured Git repositories. Scans each repository for JSON files that represent workflow definitions and upserts them into the system.")
    public String importWorkflowDefinitionsFromGit() {
        log.info("Importing workflow definitions from Git repositories");
        var result = importWorkflowDefinitionsFromGitUseCase.handle();
        var sb = new StringBuilder();
        if (!result.imported().isEmpty()) {
            sb.append("Imported ").append(result.imported().size()).append(" definition(s):\n");
            result.imported().forEach(name -> sb.append("  - ").append(name).append("\n"));
        } else {
            sb.append("No new workflow definitions found.\n");
        }
        if (!result.errors().isEmpty()) {
            sb.append("Errors (").append(result.errors().size()).append("):\n");
            result.errors().forEach(err -> sb.append("  - ").append(err).append("\n"));
        }
        return sb.toString();
    }
}
