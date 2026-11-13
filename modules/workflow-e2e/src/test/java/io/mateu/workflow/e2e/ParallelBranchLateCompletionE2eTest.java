package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The race {@link ForEachLateCompletionE2eTest} covers for forEach iterations, on ordinary parallel
 * branches. The flow cancels a branch still at work — the rollback once a sibling failed, an END reached
 * elsewhere — but cancelling races with the work: a step whose worker had already booked (or whose child
 * process had already completed) reported COMPLETED afterwards, the report was dropped as stale and the
 * booking was never undone. It is now kept as a completed run and compensated: by the rollback in a
 * failed process, by a cleanup beside the flow in one that did not fail.
 *
 * <p>And the opt-ins: {@code compensateAbandoned} on an END and {@code compensateLosers} on an XOR join
 * also undo what the abandoned branches completed in time; without them that work stands, as it always
 * has.
 */
class ParallelBranchLateCompletionE2eTest extends AbstractE2eTest {

    @Autowired NotifyParentStepService notifyParentStepService;

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    @Test
    void aBranchThatCompletesAfterTheRollbackCancelledItIsCompensated() {
        var hotels = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-1"))));
        worker.on("hotel", (req, cb, n) -> hotels.add(cb));
        worker.on("audit", (req, cb, n) -> cb.complete());
        worker.on("flight", (req, cb, n) -> cb.fail("no seats"));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-car", (req, cb, n) -> {
            undone.add("car/" + var(req, "carId"));
            cb.complete();
        });
        worker.on("cancel-hotel", (req, cb, n) -> {
            undone.add("hotel/" + var(req, "hotelId"));
            cb.complete();
        });

        createProcess("fork-late-completion", "fork-late-1");

        // The flight fails: the car is undone, and the hotel — still at its worker — is cancelled.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(step("fork-late-1", "hotel").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(undone).containsExactly("car/C-1");

        // The cancellation lost the race: the hotel was booked, and the worker says so now.
        assertThat(hotels).hasSize(1);
        hotels.get(0).complete(List.of(TestWorker.var("hotelId", "H-1")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(undone).containsExactly("car/C-1", "hotel/H-1"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(step("fork-late-1", "hotel").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // The flow stays held: nothing after the join runs on a failed process.
        assertThat(step("fork-late-1", "join").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        // The late report did not rewrite the failed process's variables: it travels on the run.
        assertThat(process("fork-late-1").getVariables()).noneMatch(v -> "hotelId".equals(v.name()));
    }

    @Test
    void aLateAnswerForABranchWithoutCompensationIsStillIgnored() {
        var audits = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-2"))));
        worker.on("hotel", (req, cb, n) -> cb.complete(List.of(TestWorker.var("hotelId", "H-2"))));
        worker.on("audit", (req, cb, n) -> audits.add(cb));
        worker.on("flight", (req, cb, n) -> cb.fail("no seats"));
        worker.on("cancel-car", (req, cb, n) -> cb.complete());
        worker.on("cancel-hotel", (req, cb, n) -> cb.complete());

        createProcess("fork-late-completion", "fork-late-2");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(step("fork-late-2", "audit").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        audits.get(0).complete();

        assertThat(step("fork-late-2", "audit").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process("fork-late-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }

    @Test
    void aChildProcessThatCompletedForABranchTheRollbackCancelledIsCompensated() {
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-3"))));
        var books = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("book", (req, cb, n) -> books.add(cb));
        worker.on("flight", (req, cb, n) -> cb.fail("no seats"));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-car", (req, cb, n) -> {
            undone.add("car/" + var(req, "carId"));
            cb.complete();
        });
        worker.on("cancel-booking", (req, cb, n) -> {
            undone.add("room/" + var(req, "bookingId"));
            cb.complete();
        });

        createProcess("fork-late-child", "fork-late-3");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(undone).containsExactly("car/C-3");
        var room = step("fork-late-3", "room");
        assertThat(room.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        // The race the cascade cannot close: the child had already completed — its room booked — when
        // the rollback cancelled the branch, and its report reaches the parent only now.
        var child = ChildProcesses.of(processRepository, room.getId()).get(0);
        var completedChild = child.withStatus(ProcessStatus.COMPLETED)
                .withVariables(List.of(new io.mateu.workflow.domain.aggregates.Variable("bookingId", "B-3")));
        processRepository.save(completedChild);
        notifyParentStepService.processReachedTerminalStatus(completedChild);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(undone).containsExactly("car/C-3", "room/B-3"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(step("fork-late-3", "room").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ branches abandoned by an END

    @Test
    void aBranchCancelledByAnEndElsewhereIsCompensatedWhenItsWorkCompletesLate() {
        var quicks = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var hotels = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quick", (req, cb, n) -> quicks.add(cb));
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-5"))));
        worker.on("hotel", (req, cb, n) -> hotels.add(cb));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-car", (req, cb, n) -> {
            undone.add("car/" + var(req, "carId"));
            cb.complete();
        });
        worker.on("cancel-hotel", (req, cb, n) -> {
            undone.add("hotel/" + var(req, "hotelId"));
            cb.complete();
        });

        createProcess("fork-late-end", "fork-late-5");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(hotels).hasSize(1));
        quicks.get(0).complete();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("fork-late-5", "hotel").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        // The car completed in time: without compensateAbandoned it stands, as it always has.
        assertThat(undone).isEmpty();

        hotels.get(0).complete(List.of(TestWorker.var("hotelId", "H-5")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(undone).containsExactly("hotel/H-5"));
        // A cleanup beside a finished flow: the process stays COMPLETED.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(steps("fork-late-5").stream()
                        .filter(s -> "cancel-hotel".equals(s.getStepId())
                                && io.mateu.workflow.domain.services.StepExecutions.isCurrent(s))
                        .findFirst().orElseThrow().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        assertThat(process("fork-late-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void anEndThatCompensatesAbandonedBranchesAlsoUndoesWhatTheyCompletedInTime() {
        var quicks = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var hotels = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quick", (req, cb, n) -> quicks.add(cb));
        worker.on("car", (req, cb, n) -> cb.complete(List.of(TestWorker.var("carId", "C-6"))));
        worker.on("hotel", (req, cb, n) -> hotels.add(cb));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-car", (req, cb, n) -> {
            undone.add("car/" + var(req, "carId"));
            cb.complete();
        });
        worker.on("cancel-hotel", (req, cb, n) -> {
            undone.add("hotel/" + var(req, "hotelId"));
            cb.complete();
        });

        createProcess("fork-late-end-cleanup", "fork-late-6");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(hotels).hasSize(1));
        quicks.get(0).complete();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(undone).containsExactly("car/C-6"));
        assertThat(process("fork-late-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        hotels.get(0).complete(List.of(TestWorker.var("hotelId", "H-6")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(undone).containsExactly("car/C-6", "hotel/H-6"));
        assertThat(process("fork-late-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ XOR joins

    @Test
    void byDefaultTheLoserOfAnXorJoinRunsOnAndIsNotCompensated() {
        var pays = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var slows = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> cb.complete());
        worker.on("slow", (req, cb, n) -> slows.add(cb));
        worker.on("pay", (req, cb, n) -> pays.add(cb));
        worker.on("release-fast", (req, cb, n) -> cb.complete());
        worker.on("release-slow", (req, cb, n) -> cb.complete());

        createProcess("fork-late-xor", "fork-late-7");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pays).hasSize(1));
        // The loser was not cancelled: it completes in time, as it always could.
        assertThat(step("fork-late-7", "slow").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        slows.get(0).complete();
        pays.get(0).complete();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-7").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("fork-late-7", "slow").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("release-slow")).isZero();
        assertThat(worker.invocationsOf("release-fast")).isZero();
    }

    @Test
    void byDefaultTheLoserOfAnXorJoinIsCompensatedWhenItCompletesAfterTheEndCancelledIt() {
        var fasts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var slows = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> fasts.add(cb));
        worker.on("slow", (req, cb, n) -> slows.add(cb));
        worker.on("pay", (req, cb, n) -> cb.complete());
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("release-slow", (req, cb, n) -> {
            undone.add("slow/" + var(req, "slowId"));
            cb.complete();
        });

        createProcess("fork-late-xor", "fork-late-8");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(slows).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fasts).hasSize(1));
        fasts.get(0).complete();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-8").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("fork-late-8", "slow").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        slows.get(0).complete(List.of(TestWorker.var("slowId", "S-8")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(undone).containsExactly("slow/S-8"));
        assertThat(worker.invocationsOf("release-fast")).isZero();
        assertThat(process("fork-late-8").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void anXorJoinThatCompensatesLosersCancelsTheirBranchAndUndoesWhatItCompleted() {
        var fasts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var pays = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> fasts.add(cb));
        worker.on("hold", (req, cb, n) -> cb.complete(List.of(TestWorker.var("slotId", "SL-9"))));
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        worker.on("pay", (req, cb, n) -> pays.add(cb));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("release-hold", (req, cb, n) -> {
            undone.add("hold/" + var(req, "slotId"));
            cb.complete();
        });
        worker.on("release-fast", (req, cb, n) -> cb.complete());

        createProcess("fork-late-xor-losers", "fork-late-9");
        // The losing branch is under way: its slot held in time, its quote still at the worker.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(quotes).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fasts).hasSize(1));
        fasts.get(0).complete();

        // The join goes on with the fast branch: the quote is cancelled, the slot released.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(undone).containsExactly("hold/SL-9"));
        assertThat(step("fork-late-9", "quote").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process("fork-late-9").getStatus()).isEqualTo(ProcessStatus.RUNNING);

        // A step with no compensation that completes late is still ignored.
        quotes.get(0).complete();
        assertThat(step("fork-late-9", "quote").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        // And the flow carries on down the winning branch.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pays).hasSize(1));
        pays.get(0).complete();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-9").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(undone).containsExactly("hold/SL-9");
        assertThat(worker.invocationsOf("release-fast")).isZero();
    }

    @Test
    void reachingEndDoesNotCancelACleanupStillUndoingALosingBranch() {
        var fasts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var releases = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> fasts.add(cb));
        worker.on("hold", (req, cb, n) -> cb.complete(List.of(TestWorker.var("slotId", "SL-10"))));
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        worker.on("pay", (req, cb, n) -> cb.complete());
        worker.on("release-hold", (req, cb, n) -> releases.add(cb));

        createProcess("fork-late-xor-losers", "fork-late-10");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(quotes).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fasts).hasSize(1));
        fasts.get(0).complete();

        // The slot's release is at its worker while the winning branch pays and reaches END.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(releases).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-10").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        var cleanup = steps("fork-late-10").stream()
                .filter(s -> "release-hold".equals(s.getStepId()) && s.isCleanupCompensation()).findFirst().orElseThrow();
        assertThat(cleanup.getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        releases.get(0).complete();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(steps("fork-late-10").stream()
                        .filter(s -> "release-hold".equals(s.getStepId()) && s.isCleanupCompensation())
                        .findFirst().orElseThrow().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        assertThat(process("fork-late-10").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aCleanupThatFailsIsAnIncidentAndTheProcessCarriesOn() {
        var fasts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("fast", (req, cb, n) -> fasts.add(cb));
        worker.on("hold", (req, cb, n) -> cb.complete(List.of(TestWorker.var("slotId", "SL-11"))));
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        worker.on("pay", (req, cb, n) -> cb.complete());
        worker.on("release-hold", (req, cb, n) -> cb.fail("slot service down"));
        worker.on("release-fast", (req, cb, n) -> cb.complete());

        createProcess("fork-late-xor-losers", "fork-late-11");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(quotes).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fasts).hasSize(1));
        fasts.get(0).complete();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fork-late-11").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        var cleanup = steps("fork-late-11").stream()
                .filter(s -> "release-hold".equals(s.getStepId()) && s.isCleanupCompensation()).findFirst().orElseThrow();
        assertThat(cleanup.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(errorsOf("fork-late-11")).anyMatch(m -> m.contains("release-hold") || m.contains("slot service down")));
        // Not a rollback: nothing else was undone, and the failure is there for an operator to see.
        assertThat(worker.invocationsOf("release-fast")).isZero();
        assertThat(worker.invocationsOf("release-hold")).isEqualTo(1);
    }
}
