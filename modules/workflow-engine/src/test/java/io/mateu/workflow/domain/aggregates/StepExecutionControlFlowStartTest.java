package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting the pure control-flow nodes (START, FORK, JOIN) and the PROCESS step: none of
 * them dispatches a worker task; the control-flow nodes complete instantly, a PROCESS step
 * requests the child process and stays PENDING until the child reaches a terminal status.
 */
class StepExecutionControlFlowStartTest {

    private Process process(List<Variable> variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(variables).build();
    }

    private Step step(StepType type, String childWorkflowDefinitionId) {
        return new Step("s-1", "wd-1", type, "Step", null, null, null, null, false,
                null, null, null, childWorkflowDefinitionId, null,
                0, null, null, null, null, 0, 0, false, null, 0);
    }

    @ParameterizedTest
    @EnumSource(value = StepType.class, names = {"START", "FORK", "JOIN"})
    void controlFlowNodesCompleteInstantlyWithoutDispatchingAWorkerTask(StepType type) {
        var se = StepExecution.create(step(type, null), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(se.getFinishedAt()).isNotNull();
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof TaskExecutionRequested);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Info.equals(log.messageType()));
    }

    @Test
    void processStepRequestsTheChildProcessAndStaysPending() {
        var se = StepExecution.create(step(StepType.PROCESS, "child-wd"), "p-1", 0);

        se.start(process(List.of(new Variable("v1", "one"), new Variable("v2", "two"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof TaskExecutionRequested);
        var request = events.stream()
                .filter(e -> e instanceof ProcessCreationRequested)
                .map(e -> (ProcessCreationRequested) e)
                .findFirst().orElseThrow();
        assertThat(request.workflowDefinitionId()).isEqualTo("child-wd");
        // Deterministic businessKey makes redeliveries idempotent (deduped by businessKey).
        assertThat(request.businessKey()).isEqualTo("parent:" + se.getId());
        assertThat(request.parentStepExecutionId()).isEqualTo(se.getId());
        // The child starts from a snapshot of ALL the parent's variables.
        assertThat(request.variables()).extracting(io.mateu.workflow.dtos.Variable::name)
                .containsExactlyInAnyOrder("v1", "v2");
    }

    @Test
    void processStepWithoutChildWorkflowDefinitionIdFailsThroughTheNormalPipeline() {
        var se = StepExecution.create(step(StepType.PROCESS, " "), "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof ProcessCreationRequested);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Error.equals(log.messageType()));
    }
}
