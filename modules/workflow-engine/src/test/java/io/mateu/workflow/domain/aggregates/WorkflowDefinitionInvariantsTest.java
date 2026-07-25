package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class WorkflowDefinitionInvariantsTest {

    private WorkflowDefinition definition(List<Step> steps) {
        return new WorkflowDefinition(
                "wd-1", "Test Workflow", 1, "desc",
                WorkflowDefinitionStatus.DRAFT,
                null, false, 0, false, null, steps
        );
    }

    private Step step(String id, String preconditionStepId, String compensationStepId) {
        return new Step(id, "wd-1", StepType.ACTION, "Step " + id, null,
                preconditionStepId, null, false, "topic", null, null, null, 0, null, null, null,
                0, 0, compensationStepId != null, compensationStepId);
    }

    @Test
    void shouldPassWhenNoSelfReference() {
        var steps = List.of(
                step("step-1", null, null),
                step("step-2", "step-1", null),
                step("step-3", "step-2", "step-1")
        );
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldFailWhenTimerStepHasNoDurationNorUntilVariable() {
        var timer = new Step("wait", "wd-1", StepType.TIMER, "Wait", null,
                null, null, false, null, null, null, null, 0, null, null, null,
                0, 0, false, null);
        assertThatThrownBy(() -> definition(List.of(timer)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("duration");
    }

    @Test
    void shouldPassWhenTimerStepHasDuration() {
        var timer = new Step("wait", "wd-1", StepType.TIMER, "Wait", null,
                null, null, false, null, null, null, null, 60000, null, null, null,
                0, 0, false, null);
        assertThatNoException().isThrownBy(() -> definition(List.of(timer)).checkInvariants());
    }

    @Test
    void shouldFailWhenMessageStepHasNoMessageName() {
        var message = new Step("wait", "wd-1", StepType.MESSAGE, "Wait", null,
                null, null, false, null, null, null, null, 0, null, null, null,
                0, 0, false, null);
        assertThatThrownBy(() -> definition(List.of(message)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("messageName");
    }

    @Test
    void shouldPassWhenMessageStepHasMessageName() {
        var message = new Step("wait", "wd-1", StepType.MESSAGE, "Wait", null,
                null, null, false, null, null, null, null, 0, null, "payment-received", null,
                0, 0, false, null);
        assertThatNoException().isThrownBy(() -> definition(List.of(message)).checkInvariants());
    }

    @Test
    void shouldFailWhenStepHasItselfAsPrecondition() {
        var steps = List.of(step("step-1", "step-1", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1")
                .hasMessageContaining("precondition");
    }

    @Test
    void shouldFailWhenStepHasItselfAsCompensation() {
        var steps = List.of(step("step-1", null, "step-1"));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1")
                .hasMessageContaining("compensation");
    }

    @Test
    void shouldFailWhenStepsFormAPreconditionCycle() {
        var steps = List.of(
                step("step-1", "step-2", null),
                step("step-2", "step-1", null)
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void shouldFailWhenStepsFormALongerPreconditionCycle() {
        var steps = List.of(
                step("step-1", "step-3", null),
                step("step-2", "step-1", null),
                step("step-3", "step-2", null)
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void shouldPassWhenPreconditionsFormATreeWithoutCycles() {
        var steps = List.of(
                step("root", null, null),
                step("a", "root", null),
                step("b", "root", null),
                step("c", "a", null)
        );
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldFailOnFirstViolatingStep() {
        var steps = List.of(
                step("step-1", null, null),
                step("step-2", "step-2", null)   // self-precondition
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-2");
    }
}
