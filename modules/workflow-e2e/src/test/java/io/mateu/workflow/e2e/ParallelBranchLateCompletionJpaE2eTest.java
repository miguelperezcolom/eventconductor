package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link ParallelBranchLateCompletionE2eTest} through the database: a late branch completion is kept
 * and compensated across separate reads of the persisted state, and a cleanup run is stored as one
 * (its {@code cleanup} marker and the units it undid survive the round trip).
 */
class ParallelBranchLateCompletionJpaE2eTest extends AbstractJpaE2eTest {

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    @Test
    void aBranchCompletedAfterTheRollbackCancelledItIsCompensatedThroughTheDatabase() {
        var hotels = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C1"))));
        worker.on("hotel", (req, cb, n) -> hotels.add(cb));
        worker.on("audit", (req, cb, n) -> cb.complete());
        worker.on("flight", (req, cb, n) -> cb.fail("no seats"));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-car", (req, cb, n) -> {
            undone.add(var(req, "carId"));
            cb.complete();
        });
        worker.on("cancel-hotel", (req, cb, n) -> {
            undone.add(var(req, "hotelId"));
            cb.complete();
        });

        createProcess("fork-late-completion", "jfork-late");
        awaitStatus("jfork-late", ProcessStatus.COMPENSATED);
        assertThat(undone).containsExactly("C1");
        assertThat(step("jfork-late", "hotel").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(hotels).hasSize(1));
        hotels.get(0).complete(List.of(TestWorker.var("hotelId", "H1")));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(undone).containsExactly("C1", "H1"));
        awaitStatus("jfork-late", ProcessStatus.COMPENSATED);
        assertThat(step("jfork-late", "hotel").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void anXorJoinCleanupIsStoredAsACleanupRunOverTheUnitsItUndid() {
        var fasts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> fasts.add(cb));
        worker.on("hold", (req, cb, n) -> cb.complete(List.of(TestWorker.var("slotId", "S1"))));
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        worker.on("pay", (req, cb, n) -> cb.complete());
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("release-hold", (req, cb, n) -> {
            undone.add(var(req, "slotId"));
            cb.complete();
        });

        createProcess("fork-late-xor-losers", "jxor-clean");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(quotes).hasSize(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(fasts).hasSize(1));
        fasts.get(0).complete();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(undone).containsExactly("S1"));
        awaitStatus("jxor-clean", ProcessStatus.COMPLETED);
        var hold = step("jxor-clean", "hold");
        var cleanup = steps("jxor-clean").stream()
                .filter(s -> "release-hold".equals(s.getStepId()) && !s.isIteration() && s.isCleanupCompensation())
                .findFirst().orElseThrow();
        assertThat(cleanup.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(cleanup.scope()).containsEntry("cleanup", "xor:first");
        assertThat(cleanup.compensatedUnitIds()).containsExactly(hold.getId());
        assertThat(steps("jxor-clean").stream().filter(s -> "quote".equals(s.getStepId())).map(StepExecution::getStatus))
                .containsExactly(StepExecutionStatus.CANCELLED);
    }
}
