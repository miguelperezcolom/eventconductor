package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionTimerStartTest {

    private Process process(java.util.List<Variable> variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(variables).build();
    }

    private Step timerStep(long durationMillis, String untilVariable) {
        return new Step("wait", "wd-1", StepType.TIMER, "Wait", null, null, null, null, false, null, null, null, null, null, durationMillis, untilVariable, null, null, null, 0, 0, false, null, 0);
    }

    @Test
    void startingATimerArmsItWithoutDispatchingAnyTask() {
        var se = StepExecution.create(timerStep(60_000, null), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof TaskExecutionRequested);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Info.equals(log.messageType()));
    }

    @Test
    void startingATimerWithDateVariableArmsIt() {
        var se = StepExecution.create(timerStep(0, "resumeAt"), "p-1", 0);

        se.start(process(List.of(new Variable("resumeAt", "2026-08-01T15:00"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(se.popEvents()).noneMatch(e -> e instanceof TaskExecutionRequested);
    }

    @Test
    void startingATimerWithMissingDateVariableFailsThroughTheNormalPipeline() {
        var se = StepExecution.create(timerStep(0, "resumeAt"), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Error.equals(log.messageType()));
    }
}
