package com.example.series;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chapter 6 — the race: whichever company confirms first wins (XOR JOIN) and the other branch is abandoned
 * (compensateLosers). Here City Cabs has not answered when Airport Express confirms; on real threads, where the
 * loser answers a second later and is then cancelled at its company, see SeriesAppEndToEndTest.
 */
@EventConductorTest(properties = SeriesTests.NO_CRON)
class AirportTransferTest {

    @Test
    void aCompanyThatHasNotAnsweredLosesAndHasNothingToUndo(WorkflowTestRuntime runtime) {
        runtime.whenStep("book-city-cabs").thenTimeout();                   // still on its way
        runtime.whenHttpCall("book-airport-express").thenRespond(201, "{\"id\": \"AE-1\"}");
        var cancelCityCabs = runtime.whenHttpCall("cancel-city-cabs").thenRespond(204);
        var cancelExpress = runtime.whenHttpCall("cancel-airport-express").thenRespond(204);
        runtime.whenStep("confirm-transfer").thenComplete();

        var transfer = runtime.start("airport-transfer", "TRANSFER-T1", Map.of("guest", "Erin", "flight", "IB3456"));

        assertThat(transfer).isCompleted().hasPassedSteps("book-airport-express", "first-confirmed", "confirm-transfer")
                .hasVariable("taxi", "Airport Express").hasVariable("taxiBookingId", "AE-1")
                .hasStepStatus("book-city-cabs", StepExecutionStatus.CANCELLED);
        assertThat(runtime.invocationsOf("book-city-cabs")).hasSize(1);    // sent, never answered
        assertThat(cancelCityCabs.requests()).isEmpty();                    // it booked nothing: nothing to undo
        assertThat(cancelExpress.requests()).isEmpty();
    }
}
