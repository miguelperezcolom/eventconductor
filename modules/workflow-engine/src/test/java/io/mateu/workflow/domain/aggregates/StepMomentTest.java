package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.time.Moments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TIMER {@code until} and any step's {@code deadline}: moments from the process's data. */
class StepMomentTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private static Step step(StepType type) {
        return new Step("s", "wd-1", type, "S", null, null, null, null, false, null, null, null, null, null,
                0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static LocalDateTime engineTime(ZonedDateTime zoned) {
        return zoned.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    @AfterEach
    void resetZone() {
        Moments.setDefaultZone(null);
    }

    @Test
    void aTimerWaitsForThreeDaysBeforeCheckInAtNineHotelTime() {
        var timer = step(StepType.TIMER).withUntil(new Moment("${checkinDate}", "-P3D", "09:00", "${hotelZone}", null));

        var dueAt = timer.timerDueAt(LocalDateTime.now(), List.of(
                new Variable("checkinDate", "2026-08-01"), new Variable("hotelZone", "Europe/Madrid")));

        assertThat(dueAt).isEqualTo(engineTime(ZonedDateTime.of(2026, 7, 29, 9, 0, 0, 0, MADRID)));
    }

    @Test
    void anUnresolvableMomentFailsTheTimerNamingIt() {
        var timer = step(StepType.TIMER).withUntil(Moment.of("${checkinDate}"));

        assertThatThrownBy(() -> timer.timerDueAt(LocalDateTime.now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Timer step 's'")
                .hasMessageContaining("${checkinDate}");
    }

    @Test
    void untilVariableIsReadInTheConfiguredZone() {
        Moments.setDefaultZone(MADRID);
        var timer = step(StepType.TIMER).withUntilVariable("checkinDate");

        var dueAt = timer.timerDueAt(LocalDateTime.now(), List.of(new Variable("checkinDate", "2026-08-01")));

        assertThat(dueAt).isEqualTo(engineTime(ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, MADRID)));
    }

    @Test
    void theEarlierOfTimeoutAndDeadlineWins() {
        var startedAt = LocalDateTime.of(2026, 7, 1, 10, 0);
        var variables = List.of(new Variable("due", "2026-07-01T10:30"));
        var action = step(StepType.ACTION).withDeadline(Moment.of("${due}"));

        assertThat(action.withTimeout(3_600_000).timeLimitAt(startedAt, variables))
                .as("deadline in 30 min beats a 1 h timeout").isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 30));
        assertThat(action.withTimeout(600_000).timeLimitAt(startedAt, variables))
                .as("a 10 min timeout beats the deadline").isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 10));
        assertThat(action.timeLimitAt(startedAt, variables)).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 30));
        assertThat(action.hasTimeLimit()).isTrue();
        assertThat(step(StepType.ACTION).hasTimeLimit()).isFalse();
        assertThat(step(StepType.ACTION).timeLimitAt(startedAt, variables)).isNull();
    }

    @Test
    void anUnresolvableDeadlineLeavesTheTimeoutInCharge() {
        var startedAt = LocalDateTime.of(2026, 7, 1, 10, 0);
        var action = step(StepType.ACTION).withDeadline(Moment.of("${due}")).withTimeout(60_000);

        assertThat(action.timeLimitAt(startedAt, List.of())).isEqualTo(startedAt.plusMinutes(1));
        assertThat(action.withTimeout(0).timeLimitAt(startedAt, List.of())).isNull();
    }

    @Test
    void aDeadlineDoesNotMoveWithTheStart() {
        // A retry restarts the clock of a timeout, not of a deadline.
        var variables = List.of(new Variable("due", "2026-07-01T12:00"));
        var action = step(StepType.ACTION).withDeadline(Moment.of("${due}"));

        assertThat(action.timeLimitAt(LocalDateTime.of(2026, 7, 1, 10, 0), variables))
                .isEqualTo(action.timeLimitAt(LocalDateTime.of(2026, 7, 1, 11, 0), variables));
    }

    @Test
    void aMomentReadsAsAStringOrAnObject() {
        var fromString = JsonSerializer.pojoFromJson(
                "{\"id\":\"w\",\"type\":\"TIMER\",\"until\":\"${checkinDate}\"}", Step.class);
        assertThat(fromString.until()).isEqualTo(Moment.of("${checkinDate}"));

        var fromObject = JsonSerializer.pojoFromJson(
                "{\"id\":\"w\",\"type\":\"TIMER\",\"until\":{\"date\":\"${checkinDate}\",\"offset\":\"-P3D\",\"ifPast\":\"timeout\"}}",
                Step.class);
        assertThat(fromObject.until()).isEqualTo(new Moment("${checkinDate}", "-P3D", null, null, "timeout"));
        assertThat(fromObject.until().timesOutIfPast()).isTrue();

        var roundTrip = JsonSerializer.pojoFromJson(JsonSerializer.toJson(fromObject), Step.class);
        assertThat(roundTrip.until()).isEqualTo(fromObject.until());
    }

    @Test
    void describesItselfInWords() {
        assertThat(new Moment("${checkinDate}", "-P3D", "09:00", "Europe/Madrid", null).describe())
                .isEqualTo("${checkinDate} -P3D at 09:00 Europe/Madrid");
    }

    private static WorkflowDefinition definition(Step... steps) {
        var all = new java.util.ArrayList<Step>();
        all.add(step(StepType.START).withId("start"));
        for (var step : steps) {
            all.add(step.withPreconditionStepId("start"));
        }
        return new WorkflowDefinition("wd-1", "M", 1, null, false, 0, false, null, 0, all);
    }

    @Test
    void theInvariantsKeepMomentsWhereTheyMeanSomething() {
        definition(step(StepType.TIMER).withId("t").withUntil(new Moment("${d}", "-P3D", "09:00", "Europe/Madrid", "timeout")),
                step(StepType.ACTION).withId("a").withDeadline(Moment.of("${d}"))).checkInvariants();

        assertThatThrownBy(() -> definition(step(StepType.ACTION).withId("a").withUntil(Moment.of("${d}"))).checkInvariants())
                .hasMessageContaining("only a TIMER");
        assertThatThrownBy(() -> definition(step(StepType.TIMER).withId("t").withUntil(Moment.of("${d}")).withDuration(1000)).checkInvariants())
                .hasMessageContaining("it must declare one");
        assertThatThrownBy(() -> definition(step(StepType.TIMER).withId("t").withDuration(1000).withDeadline(Moment.of("${d}"))).checkInvariants())
                .hasMessageContaining("does not wait");
        assertThatThrownBy(() -> definition(step(StepType.TIMER).withId("t").withUntil(new Moment("${d}", "soon", null, null, null))).checkInvariants())
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> definition(step(StepType.TIMER).withId("t").withUntil(Moment.of("${d +}"))).checkInvariants())
                .hasMessageContaining("until");
        assertThatThrownBy(() -> definition(step(StepType.ACTION).withId("a").withDeadline(new Moment("${d}", null, null, null, "fire"))).checkInvariants())
                .hasMessageContaining("ifPast");
        assertThatThrownBy(() -> definition(step(StepType.TIMER).withId("t")).checkInvariants())
                .hasMessageContaining("duration, an untilVariable or an until");
    }
}
