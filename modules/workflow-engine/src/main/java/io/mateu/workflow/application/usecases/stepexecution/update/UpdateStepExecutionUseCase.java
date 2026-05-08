package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.eventloop.AdvanceCommand;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public void handle(UpdateStepExecutionCommand command) {
        var processId = repository.findById(command.stepId()).orElseThrow().getProcessId();

        // 1. Intentar adquirir el Advisory Lock (non-blocking)
        // pg_try_advisory_lock devuelve true si lo obtiene, false si ya está cogido
        Boolean lockAcquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, processId);

        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                log.debug("Lock adquirido. Iniciando ciclo del orquestador...");

                // 2. Ejecutar la lógica dentro de una transacción
                transactionTemplate.execute(status -> {

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
                            "x"
                    ));

                    return null;
                });

            } catch (Exception e) {
                log.error("Error en el ciclo del orquestador", e);
            } finally {
                // 3. Liberar el lock SIEMPRE
                jdbcTemplate.execute("SELECT pg_advisory_unlock(" + processId + ")");
                log.debug("Lock liberado.");
            }
        } else {
            // No loguear nada aquí para no inundar los logs,
            // simplemente otro pod está trabajando.
        }

    }

}
