package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An XOR join with {@code compensateLosers} has exactly one winner: the first branch to complete. Here both
 * workers answer at the same moment, on threads of their own; however the two reports interleave with the
 * join, one branch wins and the other is compensated with what it reported (kept on its own run), and what
 * it reported does not overwrite the winner's variables. The single-thread case — both branches finished
 * before the join is even looked at — runs through the outbox in {@link XorJoinRaceJpaE2eTest}.
 */
class XorJoinRaceE2eTest extends AbstractE2eTest {

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

    @Test
    void whenBothBranchesAnswerAtTheSameTimeOnlyTheFirstToCompleteWins() throws Exception {
        var answers = new java.util.concurrent.ConcurrentHashMap<String, TestWorker.WorkerCallback>();
        worker.on("book-a", (req, cb, n) -> answers.put("A", cb));
        worker.on("book-b", (req, cb, n) -> answers.put("B", cb));
        worker.on("confirm", (req, cb, n) -> cb.complete());
        var undone = undoneLog();

        createProcess("fork-xor-race", "race-2");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(answers).containsOnlyKeys("A", "B"));

        // Both workers answer at once, on threads of their own.
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of("A", "B").stream().map(supplier -> pool.submit(() -> {
                barrier.await();
                answers.get(supplier).complete(booked(supplier, "race-2"));
                return null;
            })).toList();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // However the two reports interleaved, one won and the other is undone.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("race-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(undone).hasSize(1));
        var winner = process("race-2").getVariables().stream().filter(v -> "supplier".equals(v.name()))
                .findFirst().orElseThrow().value();
        var loser = winner.equals("A") ? "B" : "A";
        assertThat(undone).containsExactly(loser + "/" + loser + "-race-2");
        assertThat(process("race-2").getVariables())
                .anyMatch(v -> "bookingId".equals(v.name()) && (winner + "-race-2").equals(v.value()));
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        // The loser either completed (and was undone) or was cancelled first and completed late (and was
        // undone): either way the winner is the branch that completed first.
        assertThat(SUPPLIER.get(winnerOf("race-2"))).isEqualTo(winner);
    }
}
