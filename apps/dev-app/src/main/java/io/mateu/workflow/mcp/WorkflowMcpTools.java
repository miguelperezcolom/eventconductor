package io.mateu.workflow.mcp;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowMcpTools {

    private final ProcessRepository processRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final LogMessageRepository logMessageRepository;
    private final RetryProcessUseCase retryProcessUseCase;

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
    @preau
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
}
