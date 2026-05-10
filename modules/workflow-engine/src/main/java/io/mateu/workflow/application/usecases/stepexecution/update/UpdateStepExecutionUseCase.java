package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateStepExecutionUseCase {

    final StepExecutionRepository repository;
    final LogMessageRepository logMessageRepository;
    final ProcessRepository processRepository;
    final ProcessLockService processLockService;

    // Optional: present in jpa mode, absent in memory mode.
    // When null, work runs outside an explicit transaction.
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;

    public void handle(UpdateStepExecutionCommand command) {
        var processId = repository.findById(command.stepId()).orElseThrow().getProcessId();

        if (processLockService.tryLock(processId)) {
            try {
                log.debug("Lock acquired for process {}", processId);
                doUpdate(command);
            } catch (Exception e) {
                log.error("Error updating step execution", e);
            } finally {
                processLockService.unlock(processId);
                log.debug("Lock released for process {}", processId);
            }
        }
    }

    private void doUpdate(UpdateStepExecutionCommand command) {
        Runnable work = () -> {
            var execution = repository.findById(command.stepId()).orElseThrow();
            var process = processRepository.findById(execution.getProcessId()).orElseThrow();
            process.updateVariables(command.variables());
            processRepository.save(process);

            execution.updateStatus(command.status());
            repository.save(execution);

            logMessageRepository.save(new LogMessage(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now(),
                    execution.getProcessId(),
                    execution.id(),
                    MessageType.Info.name(),
                    "Task status changed to " + command.status().name(),
                    "system"
            ));
        };

        if (transactionTemplate != null) {
            transactionTemplate.execute(status -> { work.run(); return null; });
        } else {
            work.run();
        }
    }
}
