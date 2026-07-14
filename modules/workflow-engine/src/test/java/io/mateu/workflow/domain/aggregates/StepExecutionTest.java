package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionTest {

    @Test
    void shouldCreateStepExecution() {
        // given
        Step step = new Step("step-1", "wd-1", StepType.ACTION, "Step 1", "Desc", null, null, false, "topic", null, null, null, 0, 0, false, null);
        String processId = "process-1";
        int position = 1;

        // when
        StepExecution stepExecution = StepExecution.create(step, processId, position);

        // then
        assertThat(stepExecution.getId()).isNotNull();
        assertThat(stepExecution.getProcessId()).isEqualTo(processId);
        assertThat(stepExecution.getStepId()).isEqualTo(step.id());
        assertThat(stepExecution.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(stepExecution.getOrder()).isEqualTo(position);
    }

    @Test
    void shouldStartStepExecution() {
        // given
        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .processId("p-1")
                .workflowDefinitionId("wd-1")
                .stepId("s-1")
                .status(StepExecutionStatus.CREATED)
                .build();
        List<Variable> variables = List.of(new Variable("v1", "val1"));

        // when
        stepExecution.start(variables);

        // then
        assertThat(stepExecution.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(stepExecution.getVariables()).isEqualTo(variables);
        assertThat(stepExecution.popEvents()).hasSize(1);
    }

    @Test
    void shouldUpdateStatus() {
        // given
        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .status(StepExecutionStatus.CREATED)
                .build();

        // when
        stepExecution.updateStatus(StepExecutionStatus.COMPLETED);

        // then
        assertThat(stepExecution.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
