package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An END with {@code compensate: true}: the flow decides to undo what the process did — the guest
 * cancelled — without anything having failed. Every compensable step that completed is compensated,
 * newest first (a forEach's completed iterations each once, its failed ones not at all), what is still
 * in flight is cancelled, and the process ends COMPENSATED. A child that does it fails its parent step.
 */
class CompensatingEndE2eTest extends AbstractE2eTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    private void bookRoomsExcept(String soldOut) {
        worker.on("room", (req, cb, n) -> {
            if (soldOut.equals(var(req, "room"))) {
                cb.fail("sold out");
            } else {
                cb.complete(List.of(TestWorker.var("bookingId", "B-" + var(req, "room"))));
            }
        });
    }

    @Test
    void theGuestCancellingUndoesWhatWasBookedNewestFirstAndOnlyThat() {
        bookRoomsExcept("102");
        worker.on("car", TestWorker.succeed());
        worker.on("ask", TestWorker.succeed(TestWorker.var("decision", "cancel")));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-room", (req, cb, n) -> { undone.add("room " + var(req, "room")); cb.complete(); });
        worker.on("cancel-car", (req, cb, n) -> { undone.add("car"); cb.complete(); });

        createProcess("end-compensate", "ce-1", new Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("ce-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        // The car was booked last, so it is undone first; then the two rooms that were booked — the
        // sold-out one never was, so there is nothing of it to cancel.
        assertThat(undone).hasSize(3);
        assertThat(undone.get(0)).isEqualTo("car");
        assertThat(undone.subList(1, 3)).containsExactlyInAnyOrder("room 101", "room 103");
        assertThat(worker.invocationsOf("confirm")).isZero();
        assertThat(process("ce-1").getRollbackReason()).contains("END 'cancelled'");
        assertThat(step("ce-1", "cancelled").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("ce-1", "done").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void theGuestKeepingTheBookingCompletesItAndUndoesNothing() {
        bookRoomsExcept("102");
        worker.on("car", TestWorker.succeed());
        worker.on("ask", TestWorker.succeed(TestWorker.var("decision", "keep")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("end-compensate", "ce-2", new Variable("rooms", "[\"101\",\"102\"]"));

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("ce-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(worker.invocationsOf("cancel-room")).isZero();
        assertThat(worker.invocationsOf("cancel-car")).isZero();
        assertThat(process("ce-2").isRollbackRequested()).isFalse();
    }

    @Test
    void whatIsStillInFlightIsCancelledAndUndoneIfItsWorkCompletesAnyway() {
        worker.on("hold", TestWorker.succeed());
        var answers = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("ask", (req, cb, n) -> answers.add(cb));
        var transfers = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("transfer", (req, cb, n) -> transfers.add(cb));
        worker.on("release", TestWorker.succeed());
        worker.on("cancel-transfer", TestWorker.succeed());

        createProcess("end-compensate-fork", "ce-3");
        // The guest answers while the transfer company is still booking.
        await().atMost(WAIT).untilAsserted(() -> assertThat(transfers).hasSize(1));
        await().atMost(WAIT).untilAsserted(() -> assertThat(answers).hasSize(1));
        answers.get(0).complete();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("ce-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(worker.invocationsOf("release")).isEqualTo(1);
        assertThat(step("ce-3", "transfer").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(worker.invocationsOf("cancel-transfer")).isZero();

        // The transfer company had booked it before hearing of the cancellation: undone too.
        transfers.get(0).complete();

        await().atMost(WAIT).untilAsserted(() -> assertThat(worker.invocationsOf("cancel-transfer")).isEqualTo(1));
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("ce-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(worker.invocationsOf("transfer-done")).isZero();
    }

    @Test
    void aChildThatRollsItselfBackFailsItsParentStepWhichRollsBackInTurn() {
        worker.on("deposit", TestWorker.succeed());
        worker.on("hold", TestWorker.succeed());
        worker.on("release", TestWorker.succeed());
        worker.on("refund", TestWorker.succeed());

        createProcess("end-compensate-child", "ce-4");

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("ce-4").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        var stay = step("ce-4", "stay");
        assertThat(stay.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        var child = ChildProcesses.of(processRepository, stay.getId()).get(0);
        assertThat(child.getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(worker.invocationsOf("release")).isEqualTo(1);
        assertThat(worker.invocationsOf("refund")).isEqualTo(1);
        assertThat(steps("ce-4")).extracting(StepExecution::getStepId).doesNotContain("cancelled");
    }
}
