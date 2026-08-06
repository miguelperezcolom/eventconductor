package io.mateu.workflow.e2e;

import io.mateu.workflow.application.readmodel.ProcessIndexQueryService;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E for the CQRS process-index read model. Turning {@code workflow.projection.enabled} on gives
 * this class its own application context in which the projector is active — so it also proves the
 * feature is a pure config flip and works in the default, non-sharded, single-database mode.
 *
 * <p>Every assertion reads from {@link ProcessIndexQueryService} (the read side), never from the
 * write-side process repository — the point of CQRS is that the two are separate stores kept in
 * sync by the projector, and here they happen to share one database.
 */
@TestPropertySource(properties = "workflow.projection.enabled=true")
class ProcessIndexProjectionE2eTest extends AbstractE2eTest {

    @Autowired ProcessIndexQueryService processIndex;

    @Test
    void projectsACompletedProcessIntoTheReadModel() {
        worker.on("s1", TestWorker.succeed(var("a", "1")));
        worker.on("s2", TestWorker.succeed(var("b", "2")));
        worker.on("s3", TestWorker.succeed(var("c", "3")));

        createProcess("sequential-3", "idx-completed");

        var row = processIndex.findByBusinessKey("idx-completed").orElseThrow();
        assertThat(row.status()).isEqualTo(ProcessStatus.COMPLETED.name());
        assertThat(row.completionPercentage()).isEqualTo(100);
        assertThat(row.workflowDefinitionId()).isEqualTo("sequential-3");
        assertThat(row.finished()).isNotNull();
        // A terminal process is no longer in flight.
        assertThat(processIndex.findInFlight())
                .noneMatch(r -> "idx-completed".equals(r.businessKey()));
    }

    @Test
    void projectsCancellationAsTheTerminalStatus() {
        // Hold the first step so the process stays live until we cancel it.
        worker.on("s1", (req, cb, n) -> { /* never completes */ });

        createProcess("sequential-3", "idx-cancelled");

        // While live it is in flight in the read model.
        assertThat(processIndex.findInFlight())
                .anyMatch(r -> "idx-cancelled".equals(r.businessKey()));

        cancelProcessUseCase.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(
                process("idx-cancelled").getId()));

        var row = processIndex.findByBusinessKey("idx-cancelled").orElseThrow();
        assertThat(row.status()).isEqualTo(ProcessStatus.CANCELLED.name());
        assertThat(processIndex.findInFlight())
                .noneMatch(r -> "idx-cancelled".equals(r.businessKey()));
    }

    @Test
    void countsAndInFlightScopingReflectTheProjectedFleet() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "idx-count-a");
        createProcess("sequential-3", "idx-count-b");

        var counts = processIndex.countByStatus();
        assertThat(counts.getOrDefault(ProcessStatus.COMPLETED.name(), 0L)).isGreaterThanOrEqualTo(2L);

        // Definition scoping returns only rows of that definition, and both above are terminal.
        assertThat(processIndex.findInFlightByDefinition("sequential-3"))
                .extracting(ProcessIndexRow::businessKey)
                .doesNotContain("idx-count-a", "idx-count-b");
    }
}
