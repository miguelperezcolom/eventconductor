package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assertions on each iteration of a forEach, named in failure messages by index and label or item; and
 * HTTP stubs that answer one iteration, item or request apart from the others.
 */
@EventConductorTest
class ForEachIterationsTest {

    private static final List<Map<String, String>> ROOMS = List.of(
            Map.of("number", "101"), Map.of("number", "102"), Map.of("number", "103"));

    @Test
    void oneIterationFailsAndTheOthersAreUndone(WorkflowTestRuntime runtime) {
        var hotel = runtime.whenHttpCall("book").thenRespond(201, "{\"id\": \"H-1\"}");
        var soldOut = hotel.whenIteration(1).thenRespond(409, "sold out");
        var cancel = runtime.whenHttpCall("unbook").thenRespond(204);

        var run = runtime.start("rooms-http-each", "each-1", Map.of("rooms", ROOMS));

        assertThat(run).isCompensated().hasCompensated("book");
        assertThat(run).iterations("book")
                .hasSize(3)
                .hasOnlyFailed("Room 102")
                .hasCompensated("Room 101", "Room 103")
                .iteration("Room 101").isCompleted().hasOutput("bookingId", "H-1").hasItem(Map.of("number", "101"))
                .wasCompensated();
        assertThat(run).iterations("book").iteration(1).isFailed().hasStatus(StepExecutionStatus.ERROR)
                .wasNotCompensated();
        assertThat(hotel.requests()).hasSize(3);
        assertThat(soldOut.requests()).singleElement()
                .satisfies(request -> assertThat(String.valueOf(request.get("body"))).contains("102"));
        assertThat(cancel.requests()).hasSize(2);
    }

    @Test
    void aFailedAssertionNamesTheIterationsByLabelNotById(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("book").thenRespond(201, "{\"id\": \"H-1\"}")
                .whenItemHas("number", "103").thenRespond(409, "sold out");
        runtime.whenHttpCall("unbook").thenRespond(204);

        var run = runtime.start("rooms-http-each", "each-2", Map.of("rooms", ROOMS));

        assertThatThrownBy(() -> assertThat(run).iterations("book").hasOnlyFailed("Room 101"))
                .hasMessageContaining("Expected only [#0 \"Room 101\"]")
                .hasMessageContaining("but [#2 \"Room 103\"] failed")
                .hasMessageContaining("#1 \"Room 102\"")
                .hasMessageContaining("COMPLETED (compensated) {bookingId=H-1}");
        assertThatThrownBy(() -> assertThat(run).iterations("book").allCompleted())
                .hasMessageContaining("but [#2 \"Room 103\"] did not");
        assertThatThrownBy(() -> assertThat(run).iterations("book").hasCompleted("Room 103"))
                .hasMessageContaining("[#2 \"Room 103\"] did not");
        assertThatThrownBy(() -> assertThat(run).iterations("book").hasNoneCompensated())
                .hasMessageContaining("but it undid [#1 \"Room 102\", #0 \"Room 101\"]");
        assertThatThrownBy(() -> assertThat(run).iterations("book").hasCompensatedInOrder("Room 101", "Room 102"))
                .hasMessageContaining("in this order");
        assertThatThrownBy(() -> assertThat(run).iterations("book").hasSize(2))
                .hasMessageContaining("to have run 2 iterations but it ran 3");
        assertThatThrownBy(() -> assertThat(run).iterations("book").iteration("Room 999"))
                .hasMessageContaining("has no iteration <Room 999>");
        assertThatThrownBy(() -> assertThat(run).iterations("book").iteration(7))
                .hasMessageContaining("has no iteration #7");
        // The process's own failure message lists the iterations by name too, under their step.
        assertThatThrownBy(() -> assertThat(run).isCompleted())
                .hasMessageContaining("    #2 \"Room 103\"")
                .hasMessageNotContaining(run.stepExecutions("book").getFirst().getId());

        var room = assertThat(run).iterations("book").iteration("Room 103");
        assertThatThrownBy(() -> room.isCompleted()).hasMessageContaining("#2 \"Room 103\" of step 'book'");
        assertThatThrownBy(() -> room.isCancelled()).hasMessageContaining("to be CANCELLED but it is ERROR");
        assertThatThrownBy(() -> room.hasOutput("bookingId", "H-1")).hasMessageContaining("to have reported bookingId=<H-1>");
        assertThatThrownBy(() -> room.hasItem(Map.of("number", "101"))).hasMessageContaining("to have run for");
        assertThatThrownBy(() -> room.wasCompensated()).hasMessageContaining("to have been compensated");
        var undone = assertThat(run).iterations("book").iteration(0);
        assertThatThrownBy(() -> undone.isFailed()).hasMessageContaining("to have failed but it is COMPLETED");
        assertThatThrownBy(() -> undone.wasNotCompensated()).hasMessageContaining("not to have been compensated");
    }

    @Test
    void anIterationWithoutALabelIsNamedByItsItem(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenAnswer(task -> "2".equals(task.variable("room"))
                ? TaskOutcome.fail("FULL", false) : TaskOutcome.complete(Map.of("reservationId", "R")));
        runtime.whenTask("release-room").thenComplete();

        var run = runtime.start("rooms-compensate-all", "each-3", Map.of("rooms", List.of("1", "2", "3")));

        assertThatThrownBy(() -> assertThat(run).iterations("reserve").hasNoFailures())
                .hasMessageContaining("but [#1 room=2] failed");
        assertThatThrownBy(() -> assertThat(run).isCompleted())
                .hasMessageContaining("#0 room=1")
                .hasMessageContaining("#1 room=2");
    }

    @Test
    void eachIterationSpendsItsOwnAnswers(WorkflowTestRuntime runtime) {
        // Room 101's first call is turned away and retried; the others are booked at once.
        var hotel = runtime.whenHttpCall("book").thenRespond(201, "{\"id\": \"H-1\"}");
        var flaky = hotel.whenIteration(0).thenRespond(503, "busy").thenRespond(201, "{\"id\": \"H-0\"}");

        var run = runtime.start("rooms-http-each", "each-4", Map.of("rooms", ROOMS));
        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(run).isCompleted().hasNoCompensations().hasVariable("bookings", "[\"H-0\",\"H-1\",\"H-1\"]");
        assertThat(run).iterations("book").allCompleted().hasNoFailures().hasNoneCompensated();
        assertThat(flaky.requests()).hasSize(2);
        assertThat(hotel.requests()).hasSize(4);
    }

    @Test
    void aRequestOrAnItemCanBeMatchedToo(WorkflowTestRuntime runtime) {
        var hotel = runtime.whenHttpCall("book");
        hotel.whenRequest(request -> String.valueOf(request.get("body")).contains("101")).thenRespond(201, "{\"id\": \"A\"}");
        hotel.whenItem(room -> room instanceof Map<?, ?> map && "102".equals(map.get("number")))
                .thenRespond(201, "{\"id\": \"B\"}");
        var none = hotel.whenItemHas("number", "999");          // never matches: its scope has no answer

        var run = runtime.start("rooms-http-each", "each-5", Map.of("rooms", ROOMS));

        // Room 103 matches no scope, and the stub has no answer of its own.
        assertThat(run).iterations("book").hasCompleted(0, 1).hasOnlyFailed(2);
        assertThat(none.requests()).isEmpty();
        assertThat(runtime.invocationsOf("book")).hasSize(3);
    }

    @Test
    void aStepThatIsNotAForEachHasNoIterations(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("charge").thenRespond(200, "{\"id\": \"c\"}");
        var run = runtime.start("http-charge", "each-6", Map.of("bookingId", "B", "amount", 1));

        assertThatThrownBy(() -> assertThat(run).iterations("charge").hasSize(1))
                .hasMessageContaining("did not run as a forEach");
        assertThatThrownBy(() -> assertThat(run).iterations("nope").hasSize(1))
                .hasMessageContaining("has not run");
    }
}
