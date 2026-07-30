package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionMessageStartTest {

    private Process process(java.util.List<Variable> variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(variables).build();
    }

    private Step messageStep(String messageName) {
        return new Step("wait", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait for message", null, null, null, false, null, null, null, null, 0, null, messageName, null, null, 0, 0, false, null, 0);
    }

    @Test
    void startingAMessageStepWaitsWithoutDispatchingAnyTask() {
        var se = StepExecution.create(messageStep("payment-received"), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof TaskExecutionRequested);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Info.equals(log.messageType()));
    }

    @Test
    void startingAMessageStepWithoutMessageNameFailsThroughTheNormalPipeline() {
        var se = StepExecution.create(messageStep(null), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Error.equals(log.messageType()));
    }
}
