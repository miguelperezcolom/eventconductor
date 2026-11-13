package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/** Which steps make up the branches an XOR join did not go on with. */
class AbandonedBranchServiceTest {

    private final Map<String, Step> steps = new HashMap<>();
    private final Map<String, StepExecution> executions = new HashMap<>();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 13, 10, 0);

    private void step(String json, StepExecutionStatus status, LocalDateTime finishedAt) {
        var step = pojoFromJson(json, Step.class);
        steps.put(step.id(), step);
        executions.put(step.id(), StepExecution.builder().id(step.id() + "-x").stepId(step.id()).status(status)
                .finishedAt(finishedAt).build());
    }

    @Test
    void theLosingBranchIsWhatLeadsOnlyThroughTheUnsatisfiedLink() {
        step("{\"id\":\"start\",\"type\":\"START\"}", StepExecutionStatus.COMPLETED, T0);
        step("{\"id\":\"fork\",\"type\":\"FORK\",\"preconditionStepId\":\"start\"}", StepExecutionStatus.COMPLETED, T0);
        step("{\"id\":\"fast\",\"type\":\"ACTION\",\"preconditionStepId\":\"fork\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"hold\",\"type\":\"ACTION\",\"preconditionStepId\":\"fork\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"quote\",\"type\":\"ACTION\",\"preconditionStepId\":\"hold\"}", StepExecutionStatus.RUNNING, null);
        step("{\"id\":\"first\",\"type\":\"JOIN\",\"joinType\":\"XOR\",\"compensateLosers\":true,\"preconditionStepIds\":[\"fast\",\"quote\"]}",
                StepExecutionStatus.COMPLETED, T0.plusSeconds(2));
        var join = executions.get("first");

        var losing = AbandonedBranchService.losingBranches(join, steps.get("first"), executions,
                new AbandonedBranchService.Graph(steps));

        assertThat(losing).isEqualTo(Set.of("hold", "quote"));
    }

    @Test
    void aStepThatAlsoFeedsTheRestOfTheFlowIsNotPartOfTheLosingBranch() {
        step("{\"id\":\"start\",\"type\":\"START\"}", StepExecutionStatus.COMPLETED, T0);
        step("{\"id\":\"fork\",\"type\":\"FORK\",\"preconditionStepId\":\"start\"}", StepExecutionStatus.COMPLETED, T0);
        step("{\"id\":\"fast\",\"type\":\"ACTION\",\"preconditionStepId\":\"fork\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"hold\",\"type\":\"ACTION\",\"preconditionStepId\":\"fork\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"quote\",\"type\":\"ACTION\",\"preconditionStepId\":\"hold\"}", StepExecutionStatus.RUNNING, null);
        step("{\"id\":\"audit\",\"type\":\"ACTION\",\"preconditionStepId\":\"hold\"}", StepExecutionStatus.RUNNING, null);
        step("{\"id\":\"first\",\"type\":\"JOIN\",\"joinType\":\"XOR\",\"compensateLosers\":true,\"preconditionStepIds\":[\"fast\",\"quote\"]}",
                StepExecutionStatus.COMPLETED, T0.plusSeconds(2));

        var losing = AbandonedBranchService.losingBranches(executions.get("first"), steps.get("first"), executions,
                new AbandonedBranchService.Graph(steps));

        assertThat(losing).isEqualTo(Set.of("quote"));
    }

    @Test
    void twoLinksSatisfiedWhenTheJoinFiredLeaveNoLoser() {
        step("{\"id\":\"start\",\"type\":\"START\"}", StepExecutionStatus.COMPLETED, T0);
        step("{\"id\":\"a\",\"type\":\"ACTION\",\"preconditionStepId\":\"start\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"b\",\"type\":\"ACTION\",\"preconditionStepId\":\"start\"}", StepExecutionStatus.COMPLETED, T0.plusSeconds(1));
        step("{\"id\":\"first\",\"type\":\"JOIN\",\"joinType\":\"XOR\",\"preconditionStepIds\":[\"a\",\"b\"]}",
                StepExecutionStatus.COMPLETED, T0.plusSeconds(1));

        assertThat(AbandonedBranchService.losingBranches(executions.get("first"), steps.get("first"), executions,
                new AbandonedBranchService.Graph(steps))).isEmpty();
    }
}
