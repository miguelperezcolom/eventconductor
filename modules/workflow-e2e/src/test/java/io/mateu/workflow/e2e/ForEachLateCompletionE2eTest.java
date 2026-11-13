package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A forEach that fails fast cancels the iterations still in flight — but cancelling races with the
 * work. When the work wins (the worker had already booked the room, the child process had already
 * completed), the iteration's late COMPLETED used to be dropped as a stale answer: the iteration
 * stayed CANCELLED, the saga never compensated it, and the booking was left behind in the target
 * system. It is now kept as a completed run, and the rollback undoes it — whether it lands before
 * the rollback reaches its step, while it is undoing it, or after the process was already
 * COMPENSATED.
 */
class ForEachLateCompletionE2eTest extends AbstractE2eTest {

    @Autowired NotifyParentStepService notifyParentStepService;

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    private List<StepExecution> iterations(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()) && s.isIteration() && !s.isSuperseded())
                .sorted(Comparator.comparing(StepExecution::getItemIndex))
                .toList();
    }

    @Test
    void aWorkerThatCompletesAnIterationAfterItWasCancelledHasItCompensatedAfterTheRollbackEnded() {
        var pending = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("reserve", (req, cb, n) -> {
            switch (var(req, "room")) {
                case "101" -> cb.complete(List.of(TestWorker.var("reservationId", "R-101")));
                case "102" -> pending.add(cb);
                default -> cb.fail("no availability");
            }
        });
        var released = new CopyOnWriteArrayList<String>();
        worker.on("release", (req, cb, n) -> {
            released.add(var(req, "room") + "/" + var(req, "reservationId"));
            cb.complete();
        });

        createProcess("foreach-rooms", "late-1", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        // 103 fails for good: the forEach fails fast, 102 (still at its worker) is cancelled, 101 is undone.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(iterations("late-1", "reserve").get(1).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(released).containsExactly("101/R-101");

        // The cancellation lost the race: the worker had booked 102 and says so now.
        assertThat(pending).hasSize(1);
        pending.get(0).complete(List.of(TestWorker.var("reservationId", "R-102")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(released).containsExactly("101/R-101", "102/R-102"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(iterations("late-1", "reserve").get(1).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("confirm")).isZero();
    }

    @Test
    void aLateCompletionWhileTheStepIsBeingUndoneWaitsForThatRunAndIsUndoneNext() {
        var pendingReserve = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("reserve", (req, cb, n) -> {
            switch (var(req, "room")) {
                case "101" -> cb.complete(List.of(TestWorker.var("reservationId", "R-101")));
                case "102" -> pendingReserve.add(cb);
                default -> cb.fail("no availability");
            }
        });
        var released = new CopyOnWriteArrayList<String>();
        var pendingRelease = new CopyOnWriteArrayList<Runnable>();
        worker.on("release", (req, cb, n) -> {
            var what = var(req, "room") + "/" + var(req, "reservationId");
            if ("101".equals(var(req, "room"))) {
                // Held: the late answer for 102 arrives while 101 is being released.
                pendingRelease.add(() -> {
                    released.add(what);
                    cb.complete();
                });
            } else {
                released.add(what);
                cb.complete();
            }
        });

        createProcess("foreach-rooms", "late-2", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pendingRelease).hasSize(1));
        pendingReserve.get(0).complete(List.of(TestWorker.var("reservationId", "R-102")));
        // One compensation at a time: 102 is not released while 101's release is in flight.
        assertThat(released).isEmpty();
        assertThat(process("late-2").getStatus()).isEqualTo(ProcessStatus.ERROR);

        pendingRelease.get(0).run();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(released).containsExactly("101/R-101", "102/R-102"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
    }

    @Test
    void aLateCompletionWithNothingElseToUndoStillRunsTheCompensation() {
        // No iteration completed before the failure: the rollback finds nothing to undo and ends at
        // once, cancelling the compensation that never ran. The late one revives it.
        var pending = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("reserve", (req, cb, n) -> {
            if ("102".equals(var(req, "room"))) {
                pending.add(cb);
            } else {
                cb.fail("no availability");
            }
        });
        var released = new CopyOnWriteArrayList<String>();
        worker.on("release", (req, cb, n) -> {
            released.add(var(req, "room") + "/" + var(req, "reservationId"));
            cb.complete();
        });

        createProcess("foreach-rooms", "late-3", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\"]"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-3").getStatus()).isIn(ProcessStatus.COMPENSATED, ProcessStatus.ERROR));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(iterations("late-3", "reserve").get(1).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED));
        assertThat(released).isEmpty();

        pending.get(0).complete(List.of(TestWorker.var("reservationId", "R-102")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(released).containsExactly("102/R-102"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
    }

    @Test
    void aLateAnswerForAStepWithoutCompensationIsStillIgnored() {
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        var firsts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("first", (req, cb, n) -> firsts.add(cb));

        createProcess("foreach-completion", "late-4", new io.mateu.workflow.dtos.Variable("suppliers", "[\"s1\",\"s2\",\"s3\"]"));
        quotes.get(2).complete(List.of(TestWorker.var("price", "30")));
        quotes.get(0).complete(List.of(TestWorker.var("price", "10")));
        assertThat(iterations("late-4", "quote").get(1).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        quotes.get(1).complete(List.of(TestWorker.var("price", "20")));
        assertThat(iterations("late-4", "quote").get(1).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void aChildProcessThatCompletedForAnIterationTheParentCancelledIsCompensated() {
        var books = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        var rooms = new ArrayList<String>();
        worker.on("book", (req, cb, n) -> {
            synchronized (rooms) {
                rooms.add(var(req, "room"));
            }
            books.add(cb);
        });
        var cancelled = new CopyOnWriteArrayList<String>();
        worker.on("cancel-booking", (req, cb, n) -> {
            cancelled.add(var(req, "room") + "/" + var(req, "bookingId"));
            cb.complete();
        });

        createProcess("foreach-room-children", "late-5", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(books).hasSize(3));
        int i101 = rooms.indexOf("101");
        int i103 = rooms.indexOf("103");
        books.get(i101).complete(List.of(TestWorker.var("bookingId", "B-101")));
        books.get(i103).fail("no availability");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-5").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(cancelled).containsExactly("101/B-101");
        var cancelledIteration = iterations("late-5", "book-rooms").get(1);
        assertThat(cancelledIteration.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        // The race the cascade cannot close: 102's child had already completed — its booking made —
        // when the parent cancelled the iteration, and its report reaches the parent only now.
        var child = ChildProcesses.of(processRepository, cancelledIteration.getId()).get(0);
        var completedChild = child.withStatus(ProcessStatus.COMPLETED)
                .withVariables(List.of(new io.mateu.workflow.domain.aggregates.Variable("bookingId", "B-102")));
        processRepository.save(completedChild);
        notifyParentStepService.processReachedTerminalStatus(completedChild);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cancelled).containsExactly("101/B-101", "102/B-102"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("late-5").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(iterations("late-5", "book-rooms").get(1).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
