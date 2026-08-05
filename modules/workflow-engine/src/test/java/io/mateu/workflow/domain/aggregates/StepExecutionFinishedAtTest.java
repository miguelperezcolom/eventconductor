package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionFinishedAtTest {

    private StepExecution execution() {
        return StepExecution.builder()
                .id("se-1")
                .processId("p-1")
                .stepId("s-1")
                .status(StepExecutionStatus.RUNNING)
                .build();
    }

    @Test
    void terminalStatusStampsFinishedAt() {
        for (var status : new StepExecutionStatus[]{
                StepExecutionStatus.COMPLETED, StepExecutionStatus.CANCELLED,
                StepExecutionStatus.ERROR, StepExecutionStatus.TIMEOUT}) {
            var execution = execution();
            execution.updateStatus(status);
            assertThat(execution.getFinishedAt()).as("finishedAt after " + status).isNotNull();
        }
    }

    @Test
    void nonTerminalStatusClearsFinishedAt() {
        var execution = execution();
        execution.updateStatus(StepExecutionStatus.COMPLETED);
        execution.updateStatus(StepExecutionStatus.RUNNING);
        assertThat(execution.getFinishedAt()).isNull();
    }

    @Test
    void scheduleRetryClearsFinishedAt() {
        var execution = execution();
        execution.updateStatus(StepExecutionStatus.ERROR);
        execution.scheduleRetry(java.time.Duration.ofMillis(1000));
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getStatus()).isEqualTo(StepExecutionStatus.AWAITING_RETRY);
    }
}
