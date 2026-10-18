package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.IncidentRepository;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The failed steps of failed processes, from the database, in two indexed queries — never a scan of
 * the step or the log table:
 *
 * <ol>
 *   <li>the failed processes ({@code idx_process_status}) joined to their failed steps
 *       ({@code idx_step_exec_process_status}), newest failure first, bounded by the caller's limit;</li>
 *   <li>the error lines of just those processes ({@code idx_log_process}), in chunks, of which each
 *       step keeps its latest.</li>
 * </ol>
 *
 * <p>Plain JPQL with no dialect-specific SQL, so it runs as it is on PostgreSQL, H2, MariaDB and Oracle.
 * The grouping of these rows into incidents — normalising free-text messages into families — is done
 * above it, identically for every store; regular-expression functions differ from one database to the
 * next, and the families must not.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaIncidentRepository implements IncidentRepository {

    /** Process ids per error-line look-up: well under every database's bind-parameter limit. */
    static final int CHUNK = 500;

    private final StepExecutionEntityRepository stepExecutionEntityRepository;
    private final LogMessageEntityRepository logMessageEntityRepository;

    @Override
    public List<FailedStep> findFailedSteps(String workflowDefinitionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var rows = stepExecutionEntityRepository.findFailedStepsOfFailedProcesses(
                workflowDefinitionId == null, workflowDefinitionId == null ? "" : workflowDefinitionId,
                PageRequest.of(0, limit));
        if (rows.isEmpty()) {
            return List.of();
        }
        var processIds = new ArrayList<>(new LinkedHashSet<>(rows.stream()
                .map(StepExecutionEntityRepository.FailedStepView::getProcessId).toList()));
        Map<String, LogMessageEntityRepository.ErrorLineView> latest = new HashMap<>();
        for (int from = 0; from < processIds.size(); from += CHUNK) {
            var chunk = processIds.subList(from, Math.min(processIds.size(), from + CHUNK));
            for (var line : logMessageEntityRepository.findErrorLinesOfProcesses(chunk)) {
                latest.merge(line.getStepExecutionId(), line, (a, b) -> isLater(b.getTimestamp(), a.getTimestamp()) ? b : a);
            }
        }
        return rows.stream().map(row -> {
            var line = latest.get(row.getStepExecutionId());
            return new FailedStep(row.getProcessId(), row.getBusinessKey(), row.getDefinitionId(), row.getStepId(),
                    row.getStepExecutionId(), StepExecutionStatus.valueOf(row.getStatus()), row.getFinishedAt(),
                    line == null ? null : line.getMessage());
        }).toList();
    }

    private static boolean isLater(LocalDateTime candidate, LocalDateTime current) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.isAfter(current);
    }
}
