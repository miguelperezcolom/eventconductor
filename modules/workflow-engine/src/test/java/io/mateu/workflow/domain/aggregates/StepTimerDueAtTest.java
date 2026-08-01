package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepTimerDueAtTest {

    private Step timerStep(long durationMillis, String untilVariable) {
        return new Step("wait", "wd-1", StepType.TIMER, "Wait", null, null, null, null, false, null, null, null, null, null, durationMillis, untilVariable, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void durationIsCountedFromStartedAt() {
        var startedAt = LocalDateTime.of(2026, 7, 16, 10, 0);

        var dueAt = timerStep(60_000, null).timerDueAt(startedAt, List.of());

        assertThat(dueAt).isEqualTo(startedAt.plusMinutes(1));
    }

    @Test
    void untilVariableAcceptsIsoDateTime() {
        var dueAt = timerStep(0, "resumeAt")
                .timerDueAt(LocalDateTime.now(), List.of(new Variable("resumeAt", "2026-08-01T15:00")));

        assertThat(dueAt).isEqualTo(LocalDateTime.of(2026, 8, 1, 15, 0));
    }

    @Test
    void untilVariableAcceptsIsoDateAsStartOfDay() {
        var dueAt = timerStep(0, "checkinDate")
                .timerDueAt(LocalDateTime.now(), List.of(new Variable("checkinDate", "2026-08-01")));

        assertThat(dueAt).isEqualTo(LocalDate.of(2026, 8, 1).atStartOfDay());
    }

    @Test
    void untilVariableAcceptsOffsetDateTime() {
        var offset = OffsetDateTime.parse("2026-08-01T15:00:00+02:00");

        var dueAt = timerStep(0, "resumeAt")
                .timerDueAt(LocalDateTime.now(), List.of(new Variable("resumeAt", offset.toString())));

        assertThat(dueAt).isEqualTo(offset.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    }

    @Test
    void untilVariableTakesPrecedenceOverDuration() {
        var dueAt = timerStep(1, "resumeAt")
                .timerDueAt(LocalDateTime.now(), List.of(new Variable("resumeAt", "2026-08-01T15:00")));

        assertThat(dueAt).isEqualTo(LocalDateTime.of(2026, 8, 1, 15, 0));
    }

    @Test
    void missingVariableIsRejected() {
        assertThatThrownBy(() -> timerStep(0, "resumeAt").timerDueAt(LocalDateTime.now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resumeAt");
    }

    @Test
    void unparseableVariableIsRejected() {
        assertThatThrownBy(() -> timerStep(0, "resumeAt")
                .timerDueAt(LocalDateTime.now(), List.of(new Variable("resumeAt", "tomorrow"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tomorrow");
    }

    @Test
    void timerWithoutDurationOrVariableIsRejected() {
        assertThatThrownBy(() -> timerStep(0, null).timerDueAt(LocalDateTime.now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
