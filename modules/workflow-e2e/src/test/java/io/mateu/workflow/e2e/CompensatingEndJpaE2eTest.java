package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CompensatingEndE2eTest} through the database: the rollback an END with {@code compensate: true}
 * asks for ({@code rollback_reason}) is persisted with the END's pass, and drives the compensations
 * across separate reads to COMPENSATED.
 */
class CompensatingEndJpaE2eTest extends AbstractJpaE2eTest {

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    @Test
    void theGuestCancellingUndoesWhatWasBookedThroughTheDatabase() {
        worker.on("room", (req, cb, n) -> {
            if ("102".equals(var(req, "room"))) {
                cb.fail("sold out");
            } else {
                cb.complete(List.of(TestWorker.var("bookingId", "B-" + var(req, "room"))));
            }
        });
        worker.on("car", TestWorker.succeed());
        worker.on("ask", TestWorker.succeed(TestWorker.var("decision", "cancel")));
        var undone = new CopyOnWriteArrayList<String>();
        worker.on("cancel-room", (req, cb, n) -> { undone.add("room " + var(req, "room")); cb.complete(); });
        worker.on("cancel-car", (req, cb, n) -> { undone.add("car"); cb.complete(); });

        createProcess("end-compensate", "jce-1", new Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        awaitStatus("jce-1", ProcessStatus.COMPENSATED);
        assertThat(undone).hasSize(3);
        assertThat(undone.get(0)).isEqualTo("car");
        assertThat(undone.subList(1, 3)).containsExactlyInAnyOrder("room 101", "room 103");
        assertThat(worker.invocationsOf("confirm")).isZero();
        assertThat(process("jce-1").getRollbackReason()).contains("END 'cancelled'");
    }
}
