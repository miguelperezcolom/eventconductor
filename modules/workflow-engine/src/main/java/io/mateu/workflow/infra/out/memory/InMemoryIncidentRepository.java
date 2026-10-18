package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.IncidentRepository;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The failed steps of failed processes, from the in-heap store. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@RequiredArgsConstructor
public class InMemoryIncidentRepository implements IncidentRepository {

    private final ProcessRepository processRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final LogMessageRepository logMessageRepository;

    @Override
    public List<FailedStep> findFailedSteps(String workflowDefinitionId, int limit) {
        var found = new ArrayList<FailedStep>();
        for (var process : processRepository.findAll()) {
            if (!ProcessStatus.ERROR.equals(process.getStatus())
                    || (workflowDefinitionId != null && !workflowDefinitionId.equals(process.getWorkflowDefinitionId()))) {
                continue;
            }
            var failed = stepExecutionRepository.findByProcessId(process.getId()).stream()
                    .filter(se -> StepExecutionStatus.ERROR.equals(se.getStatus())
                            || StepExecutionStatus.TIMEOUT.equals(se.getStatus()))
                    .toList();
            if (failed.isEmpty()) {
                continue;
            }
            var errors = logMessageRepository.findByProcessId(process.getId());
            for (var se : failed) {
                var error = errors.stream()
                        .filter(line -> se.getId().equals(line.getStepExecutionId()))
                        .filter(line -> MessageType.isError(line.getMessageType()))
                        .max(Comparator.comparing(LogMessage::getTimestamp,
                                Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())))
                        .map(LogMessage::getMessage)
                        .orElse(null);
                found.add(new FailedStep(process.getId(), process.getBusinessKey(), se.getWorkflowDefinitionId(),
                        se.getStepId(), se.getId(), se.getStatus(), se.getFinishedAt(), error));
            }
        }
        return found.stream()
                .sorted(Comparator.comparing(FailedStep::failedAt,
                        Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())))
                .limit(Math.max(0, limit))
                .toList();
    }
}
