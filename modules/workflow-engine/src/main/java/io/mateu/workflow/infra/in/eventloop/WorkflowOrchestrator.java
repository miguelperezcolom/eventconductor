package io.mateu.workflow.infra.in.eventloop;

import io.mateu.workflow.application.usecases.eventloop.AdvanceCommand;
import io.mateu.workflow.application.usecases.eventloop.AdvanceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);
    private static final long LOCK_ID = 123456789L; // Un ID único para tu app

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AdvanceUseCase workflowService;

    public WorkflowOrchestrator(JdbcTemplate jdbcTemplate,
                                TransactionTemplate transactionTemplate,
                                AdvanceUseCase workflowService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.workflowService = workflowService;
    }

    // Se ejecuta cada 100ms (ajusta según necesidad)
    //@Scheduled(fixedDelay = 100)
    public void runEventLoop() {
        // 1. Intentar adquirir el Advisory Lock (non-blocking)
        // pg_try_advisory_lock devuelve true si lo obtiene, false si ya está cogido
        Boolean lockAcquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_ID);

        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                log.debug("Lock adquirido. Iniciando ciclo del orquestador...");

                // 2. Ejecutar la lógica dentro de una transacción
                transactionTemplate.execute(status -> {
                    workflowService.handle(new AdvanceCommand());
                    return null;
                });

            } catch (Exception e) {
                log.error("Error en el ciclo del orquestador", e);
            } finally {
                // 3. Liberar el lock SIEMPRE
                jdbcTemplate.execute("SELECT pg_advisory_unlock(" + LOCK_ID + ")");
                log.debug("Lock liberado.");
            }
        } else {
            // No loguear nada aquí para no inundar los logs,
            // simplemente otro pod está trabajando.
        }
    }
}