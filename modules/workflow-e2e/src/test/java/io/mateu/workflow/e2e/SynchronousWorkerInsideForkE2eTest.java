package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A worker that answers on the dispatching thread (the embedded executor, the test worker) drives the
 * process forward while the engine is still fanning out the FORK that dispatched it. When that branch
 * reaches END on the spot, the siblings the fan-out had not dispatched yet must not be left PENDING in a
 * process that has already completed: they are cancelled with the rest of the open branches, and none
 * of them is dispatched after the END.
 */
class SynchronousWorkerInsideForkE2eTest extends AbstractE2eTest {

    @Test
    void aBranchThatReachesEndSynchronouslyDuringTheForkLeavesNoSiblingPending() {
        var hotels = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quick", (req, cb, n) -> cb.complete());                 // answers inside the dispatch
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-S1"))));
        worker.on("hotel", (req, cb, n) -> hotels.add(cb));

        createProcess("fork-late-end", "sync-fork-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("sync-fork-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertNoStepLeftOpen("sync-fork-1");
    }

    @Test
    void anXorWinnerThatRunsToEndSynchronouslyCancelsTheLoserTheForkHadNotDispatchedYet() {
        var slows = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> cb.complete());                  // wins inside the dispatch
        worker.on("pay", (req, cb, n) -> cb.complete());                   // and the flow reaches END there
        worker.on("slow", (req, cb, n) -> slows.add(cb));

        createProcess("fork-late-xor", "sync-fork-3");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("sync-fork-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertNoStepLeftOpen("sync-fork-3");
        // Nothing is dispatched into a finished process.
        assertThat(slows).isEmpty();
    }

    @Test
    void everyBranchAnsweringSynchronouslyStillEndsCleanly() {
        worker.on("quick", (req, cb, n) -> cb.complete());
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-S2"))));
        worker.on("hotel", (req, cb, n) -> cb.complete(List.of(TestWorker.var("hotelId", "H-S2"))));

        createProcess("fork-late-end", "sync-fork-2");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("sync-fork-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertNoStepLeftOpen("sync-fork-2");
    }

    @Test
    void aBranchThatReachesEndSynchronouslyBesideAParallelForEachLeavesNoIterationOpen() {
        var rooms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("reserve", (req, cb, n) -> rooms.add(cb));
        worker.on("quick", (req, cb, n) -> cb.complete());

        createProcess("sync-fork-foreach", "sync-fork-4",
                new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("sync-fork-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertNoStepLeftOpen("sync-fork-4");
        assertThat(worker.invocationsOf("after")).isZero();
    }

    @Test
    void aChoiceThatRoutesToEndSynchronouslyCancelsTheOtherBranch() {
        var slows = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("score", (req, cb, n) -> cb.complete(List.of(TestWorker.var("verdict", "no"))));
        worker.on("slow", (req, cb, n) -> slows.add(cb));

        createProcess("sync-fork-choice", "sync-fork-5");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("sync-fork-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertNoStepLeftOpen("sync-fork-5");
        assertThat(worker.invocationsOf("proceed")).isZero();
    }

    private void assertNoStepLeftOpen(String businessKey) {
        // Give any straggling dispatch a moment to show up before checking.
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(steps(businessKey).stream()
                        .filter(io.mateu.workflow.domain.services.StepExecutions::isCurrent)
                        .filter(s -> s.getStatus() == StepExecutionStatus.PENDING
                                || s.getStatus() == StepExecutionStatus.RUNNING)
                        .map(StepExecution::getStepId).toList())
                        .as("open steps in a COMPLETED process")
                        .isEmpty());
    }
}
