package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
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
                Estados posibles de un proceso: RUNNING, COMPLETED, ERROR, COMPENSATING, COMPENSATED.
                Cada proceso tiene un ID único, un businessKey (clave de negocio) y un porcentaje de completado.
                Los pasos (StepExecution) contienen el workerId que los ejecutó y su estado individual.
                """;
    }


    private final ProcessRepository processRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final LogMessageRepository logMessageRepository;
    private final RetryProcessUseCase retryProcessUseCase;
    private final ImportWorkflowDefinitionsFromGitUseCase importWorkflowDefinitionsFromGitUseCase;

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
