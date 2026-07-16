package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-CRON-01 — the cron.json definition (fires every second) creates process instances
 * automatically, with deterministic business keys so occurrences are never duplicated.
 * Cron starts are disabled suite-wide in application.properties; this class re-enables
 * them in its own Spring context.
 */
@TestPropertySource(properties = {
        "workflow.cron-enabled=true",
        "workflow.cron-scan-interval-ms=200"
})
class CronStartE2eTest extends AbstractE2eTest {

    @Test
    void cronExpressionOnDefinitionStartsProcessesAutomatically() {
        worker.on("tick", TestWorker.succeed());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var cronProcesses = cronProcesses();
            assertThat(cronProcesses).isNotEmpty();
            assertThat(cronProcesses.get(0).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });

        var businessKeys = cronProcesses().stream().map(Process::getBusinessKey).toList();
        assertThat(businessKeys).allMatch(key -> key.startsWith("cron-cron-"));
        assertThat(businessKeys).doesNotHaveDuplicates();
    }

    private List<Process> cronProcesses() {
        return processRepository.findAll().stream()
                .filter(process -> "cron".equals(process.getWorkflowDefinitionId()))
                .toList();
    }
}
