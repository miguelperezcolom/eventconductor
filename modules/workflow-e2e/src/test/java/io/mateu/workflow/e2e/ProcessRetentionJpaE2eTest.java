package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessRetentionService;
import io.mateu.workflow.application.services.ProcessTrace;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention over a real database: processes run to the end through the outbox, and the purge
 * deletes them with their step executions and history — and only the ones that are due.
 */
class ProcessRetentionJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessPurgeRepository purgeRepository;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired WorkflowMetrics metrics;
    @Autowired ObjectProvider<ProcessIndexRepository> index;
    @Autowired ObjectProvider<ProcessTrace> trace;
    @Autowired JdbcTemplate jdbc;

    @Test
    void finishedProcessesPastTheirRetentionArePurgedWithEverythingThatBelongsToThem() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        createProcess("sequential-3", "purge-jpa-1");
        awaitStatus("purge-jpa-1", ProcessStatus.COMPLETED);
        var id = process("purge-jpa-1").getId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_execution_entity WHERE process_id = ?",
                Integer.class, id)).isPositive();

        var properties = new ProcessRetentionProperties();
        properties.getRetentionByStatus().put("completed", Duration.ofDays(7));
        var service = new ProcessRetentionService(purgeRepository, definitions, metrics, index, trace, properties);

        assertThat(service.purgeDue(LocalDateTime.now()).total()).isZero();
        assertThat(service.purgeDue(LocalDateTime.now().plusDays(8)).purged())
                .containsEntry("sequential-3", java.util.Map.of(ProcessStatus.COMPLETED, 1));

        assertThat(processOpt("purge-jpa-1")).isEmpty();
        for (var table : new String[]{"step_execution_entity", "log_message_entity", "process_index"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE process_id = ?", Integer.class, id))
                    .as(table).isZero();
        }
        // The business key can start a new process.
        createProcess("sequential-3", "purge-jpa-1");
        awaitStatus("purge-jpa-1", ProcessStatus.COMPLETED);
    }
}
