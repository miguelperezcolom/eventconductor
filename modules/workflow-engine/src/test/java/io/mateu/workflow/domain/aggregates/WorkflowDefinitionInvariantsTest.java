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
                null, false, 0, false, steps
        );
    }

    private Step step(String id, String preconditionStepId, String compensationStepId) {
        return new Step(id, "wd-1", StepType.ACTION, "Step " + id, null,
                preconditionStepId, null, false, "topic", null, null,
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
