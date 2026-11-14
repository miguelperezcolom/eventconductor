package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link XorJoinRaceE2eTest} through the outbox. An XOR join with {@code compensateLosers} has exactly one winner: the first branch to complete. Every
 * other branch loses — also one that had completed too by the time the join was looked at, which on the
 * embedded engine's default (a single, inline worker thread) is what always happens: both bookings finish
 * before the step-over reaches the join. The loser is compensated with what it reported (kept on its own
 * run), and what it reported does not overwrite the winner's variables.
 */
class XorJoinRaceJpaE2eTest extends AbstractJpaE2eTest {

    private static final Map<String, String> SUPPLIER = Map.of("book-a", "A", "book-b", "B");

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    private List<String> undoneLog() {
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-a", (req, cb, n) -> {
            undone.add("A/" + var(req, "bookingId"));
            cb.complete();
        });
        worker.on("cancel-b", (req, cb, n) -> {
            undone.add("B/" + var(req, "bookingId"));
            cb.complete();
        });
        return undone;
    }

    private static List<io.mateu.workflow.domain.aggregates.Variable> booked(String supplier, String key) {
        return List.of(TestWorker.var("supplier", supplier), TestWorker.var("bookingId", supplier + "-" + key));
    }

    /** The branch that completed first — by finishedAt, ties to the join's first link. */
    private String winnerOf(String businessKey) {
        var a = step(businessKey, "book-a").getFinishedAt();
        var b = step(businessKey, "book-b").getFinishedAt();
        return b.isBefore(a) ? "book-b" : "book-a";
    }

    private void assertExactlyOneWinner(String businessKey, List<String> undone) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step(businessKey, "book-a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "book-b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var winner = SUPPLIER.get(winnerOf(businessKey));
        var loser = winner.equals("A") ? "B" : "A";

        // The loser — and only the loser — is undone, with the booking id it reported itself.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(undone).containsExactly(loser + "/" + loser + "-" + businessKey));
        // The flow went on once, with the winner's outcome: the loser did not overwrite it.
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        var variables = process(businessKey).getVariables();
        assertThat(variables).anyMatch(v -> "supplier".equals(v.name()) && winner.equals(v.value()));
        assertThat(variables).anyMatch(v -> "bookingId".equals(v.name()) && (winner + "-" + businessKey).equals(v.value()));
        assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void onASingleWorkerThreadBothBranchesFinishBeforeTheJoinAndOnlyTheFirstWins() {
        worker.on("book-a", (req, cb, n) -> cb.complete(booked("A", "race-1")));
        worker.on("book-b", (req, cb, n) -> cb.complete(booked("B", "race-1")));
        worker.on("confirm", (req, cb, n) -> cb.complete());
        var undone = undoneLog();

        createProcess("fork-xor-race", "race-1");

        assertExactlyOneWinner("race-1", undone);
    }
}
