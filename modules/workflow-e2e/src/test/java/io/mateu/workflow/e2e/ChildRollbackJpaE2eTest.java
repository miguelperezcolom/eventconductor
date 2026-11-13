package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link ChildRollbackE2eTest} through the database: the child's rollback request ({@code rollback_reason})
 * survives the round trip, the parent waits for the child's rollback across separate reads, and the
 * child's end wakes it.
 */
class ChildRollbackJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void aChildCutShortByItsParentsRollbackIsRolledBackThroughTheDatabase() {
        worker.on("car", (req, cb, n) -> cb.complete());
        worker.on("hold", (req, cb, n) -> cb.complete());
        worker.on("confirm", (req, cb, n) -> { });
        var flights = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("flight", (req, cb, n) -> flights.add(cb));
        worker.on("cancel-car", (req, cb, n) -> cb.complete());
        worker.on("cancel-booking", (req, cb, n) -> cb.complete());
        var releases = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("release-hold", (req, cb, n) -> releases.add(cb));

        createProcess("fork-child-rollback", "jchild-rb");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(worker.invocationsOf("confirm")).isEqualTo(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(flights).hasSize(1));
        flights.get(0).fail("no seats");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(releases).hasSize(1));
        var child = ChildProcesses.of(processRepository, step("jchild-rb", "room").getId()).get(0);
        assertThat(child.isRollbackRequested()).isTrue();
        assertThat(child.getRollbackReason()).contains("rolling back");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(worker.invocationsOf("cancel-car")).isEqualTo(1));
        assertThat(process("jchild-rb").getStatus()).isEqualTo(ProcessStatus.ERROR);

        releases.get(0).complete();

        awaitStatus("jchild-rb", ProcessStatus.COMPENSATED);
        assertThat(processRepository.findById(child.getId()).orElseThrow().getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(worker.invocationsOf("cancel-booking")).isZero();
        assertThat(step("jchild-rb", "room").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }
}
