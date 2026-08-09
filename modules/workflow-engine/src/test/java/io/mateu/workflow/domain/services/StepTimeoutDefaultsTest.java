package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback exists to stop a lost dispatch or a lost worker reply from parking a process
 * forever, so what matters is that it reaches the steps that wait on a worker and leaves alone
 * the ones whose waiting is the point.
 */
class StepTimeoutDefaultsTest {

    private static final long FIVE_MINUTES = 300_000;

    @Test
    void armsActionAndRuleStepsThatDeclareNoTimeout() {
        var applied = StepTimeoutDefaults.applyTo(
                definition(step("a", StepType.ACTION, 0), step("r", StepType.RULE, 0)),
                FIVE_MINUTES);

        assertThat(applied.steps()).extracting(Step::timeout)
                .containsExactly(FIVE_MINUTES, FIVE_MINUTES);
    }

    @Test
    void neverOverridesATimeoutTheAuthorChose() {
        var applied = StepTimeoutDefaults.applyTo(
                definition(step("a", StepType.ACTION, 30_000)), FIVE_MINUTES);

        assertThat(applied.steps().getFirst().timeout()).isEqualTo(30_000);
    }

    @Test
    void leavesAloneTheStepsWhoseWaitingIsUnbounded() {
        // A person, a child process and a correlated message all legitimately take longer than
        // any fallback anyone would pick; arming them would turn healthy waiting into retries.
        var applied = StepTimeoutDefaults.applyTo(
                definition(
                        step("u", StepType.USER_TASK, 0),
                        step("p", StepType.PROCESS, 0),
                        step("m", StepType.WAIT_FOR_MESSAGE, 0),
                        step("t", StepType.TIMER, 0),
                        step("s", StepType.START, 0)),
                FIVE_MINUTES);

        assertThat(applied.steps()).extracting(Step::timeout).containsOnly(0L);
    }

    @Test
    void doesNothingWhenTheFallbackIsOff() {
        var original = definition(step("a", StepType.ACTION, 0));

        assertThat(StepTimeoutDefaults.applyTo(original, 0)).isSameAs(original);
    }

    @Test
    void keepsEverythingElseAboutTheDefinition() {
        var applied = StepTimeoutDefaults.applyTo(
                definition(step("a", StepType.ACTION, 0)), FIVE_MINUTES);

        assertThat(applied.id()).isEqualTo("wf");
        assertThat(applied.version()).isEqualTo(7);
        assertThat(applied.defaultMaxStepExecutions()).isEqualTo(3);
        assertThat(applied.steps().getFirst().id()).isEqualTo("a");
    }

    private static WorkflowDefinition definition(Step... steps) {
        return new WorkflowDefinition("wf", "A workflow", 7, null,
                false, 0, false, null, 3, List.of(steps));
    }

    private static Step step(String id, StepType type, long timeout) {
        return new Step(
                id, "wf", type, id,
                null,               // description
                null, null, null,   // precondition: id, ids, expression
                false,              // parallel
                "topic",
                null, null, null,   // formId, ruleId, childWorkflowDefinitionId
                null,               // outputVariables
                0,                  // duration
                null, null, null,   // untilVariable, messageName, correlationExpression
                null,               // messageVariables
                timeout,
                0,                  // retries
                false,              // compensable
                null,               // compensationStepId
                0,                  // maxSuccessfulExecutions
                null);              // joinType
    }
}
