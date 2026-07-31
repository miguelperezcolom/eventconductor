package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateProcessUseCase {

    final ProcessRepository processRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowMetrics workflowMetrics;

    public void handle(CreateProcessCommand command) {
        // Idempotency: a redelivered creation event carries the same processId and/or
        // businessKey — skip silently instead of creating a duplicate process.
        if (command.processId() != null && !command.processId().isBlank()) {
            if (processRepository.findById(command.processId()).isPresent()) {
                log.warn("Process with process Id '{}' already exists, ignoring duplicate creation request",
                        command.processId());
                return;
            }
        }
        if (command.businessKey() != null && !command.businessKey().isBlank()) {
            if (processRepository.findByBusinessKey(command.businessKey()).isPresent()) {
                log.warn("Process with business key '{}' already exists, ignoring duplicate creation request",
                        command.businessKey());
                return;
            }
        }

        var workflowDefinition = workflowDefinitionRepository.findById(command.workflowDefinitionId()).orElseThrow();
        AtomicInteger position = new AtomicInteger(1);
        var stepExecutions = workflowDefinition.steps().stream()
                .map(step -> StepExecution.create(step, command.processId(), position.getAndIncrement())).toList();

        stepExecutions.forEach(stepExecutionRepository::save);

        var process = Process
                .create(
                        command.processId(),
                        workflowDefinition,
                        command.businessKey(),
                        command.variables() != null?command.variables(): List.of(),
                        command.parentStepExecutionId()
                );
        if (workflowDefinition.paused()) {
            // Born paused: creation is deliberately still accepted while the definition is
            // paused (cron included), but nothing may start — the orchestration gate holds
            // everything until the definition (or the process) is resumed. The @With copies
            // start with an empty event list, so carry the ProcessCreated event over.
            var events = process.popEvents();
            process = process.withStatus(ProcessStatus.PAUSED).withPausedAt(LocalDateTime.now());
            events.forEach(process::send);
            log.info("Workflow definition '{}' is paused — process {} created PAUSED",
                    workflowDefinition.id(), process.getId());
        }
        processRepository.save(process);

        workflowMetrics.processStarted(command.workflowDefinitionId());

        // enviar evento proceso creado (para step over)


    }

}
