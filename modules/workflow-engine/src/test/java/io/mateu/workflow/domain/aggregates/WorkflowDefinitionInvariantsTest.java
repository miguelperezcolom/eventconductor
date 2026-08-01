package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class WorkflowDefinitionInvariantsTest {

    private WorkflowDefinition definition(List<Step> steps) {
        return new WorkflowDefinition(
                "wd-1", "Test Workflow", 1, "desc",
                WorkflowDefinitionStatus.DRAFT,
                null, false, 0, false, null, 0, steps
        );
    }

    /** The entry point every flow must have since the roots rule. */
    private Step startStep() {
        return new Step("start", "wd-1", StepType.START, "Start", null,
                null, null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
    }

    private Step step(String id, String preconditionStepId, String compensationStepId) {
        return new Step(id, "wd-1", StepType.ACTION, "Step " + id, null,
                preconditionStepId, null, null, false, "topic", null, null, null, null, 0, null, null, null, null,
                0, 0, compensationStepId != null, compensationStepId, 0, null);
    }

    private Step step(String id, List<String> preconditionStepIds) {
        return new Step(id, "wd-1", StepType.ACTION, "Step " + id, null,
                null, preconditionStepIds, null, false, "topic", null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
    }

    private Step processStep(String id, String preconditionStepId, String childWorkflowDefinitionId) {
        return new Step(id, "wd-1", StepType.PROCESS, "Step " + id, null,
                preconditionStepId, null, null, false, null, null, null, childWorkflowDefinitionId, null,
                0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void shouldPassWhenNoSelfReference() {
        var steps = List.of(
                startStep(),
                step("step-1", "start", null),
                step("step-2", "step-1", null),
                step("step-3", "step-2", "step-1")
        );
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldFailWhenTimerStepHasNoDurationNorUntilVariable() {
        var timer = new Step("wait", "wd-1", StepType.TIMER, "Wait", null,
                "start", null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
        assertThatThrownBy(() -> definition(List.of(startStep(), timer)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("duration");
    }

    @Test
    void shouldPassWhenTimerStepHasDuration() {
        var timer = new Step("wait", "wd-1", StepType.TIMER, "Wait", null,
                "start", null, null, false, null, null, null, null, null, 60000, null, null, null, null,
                0, 0, false, null, 0, null);
        assertThatNoException().isThrownBy(() -> definition(List.of(startStep(), timer)).checkInvariants());
    }

    @Test
    void shouldFailWhenMessageStepHasNoMessageName() {
        var message = new Step("wait", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null,
                null, null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
        assertThatThrownBy(() -> definition(List.of(message)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("messageName");
    }

    @Test
    void shouldPassWhenMessageStepHasMessageNameAndCorrelationExpression() {
        // A WAIT_FOR_MESSAGE step is a legal flow entry point: no START needed.
        var message = new Step("wait", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null,
                null, null, null, false, null, null, null, null, null, 0, null, "payment-received", "businessKey", null,
                0, 0, false, null, 0, null);
        assertThatNoException().isThrownBy(() -> definition(List.of(message)).checkInvariants());
    }

    @Test
    void shouldFailWhenWaitForMessageStepHasNoCorrelationExpression() {
        var message = new Step("wait", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null,
                null, null, null, false, null, null, null, null, null, 0, null, "payment-received", null, null,
                0, 0, false, null, 0, null);
        assertThatThrownBy(() -> definition(List.of(message)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("correlationExpression");
    }

    @Test
    void shouldFailWhenSendMessageStepHasNoCorrelationExpression() {
        var message = new Step("send", "wd-1", StepType.SEND_MESSAGE, "Send", null,
                "start", null, null, false, null, null, null, null, null, 0, null, "payment-received", null, null,
                0, 0, false, null, 0, null);
        assertThatThrownBy(() -> definition(List.of(startStep(), message)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send")
                .hasMessageContaining("correlationExpression");
    }

    @Test
    void shouldFailWhenSendMessageStepHasNoMessageName() {
        var message = new Step("send", "wd-1", StepType.SEND_MESSAGE, "Send", null,
                "start", null, null, false, null, null, null, null, null, 0, null, null, "businessKey", null,
                0, 0, false, null, 0, null);
        assertThatThrownBy(() -> definition(List.of(startStep(), message)).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send")
                .hasMessageContaining("messageName");
    }

    @Test
    void shouldPassWhenSendMessageStepHasMessageNameAndCorrelationExpression() {
        var message = new Step("send", "wd-1", StepType.SEND_MESSAGE, "Send", null,
                "start", null, null, false, null, null, null, null, null, 0, null, "payment-received", "businessKey", null,
                0, 0, false, null, 0, null);
        assertThatNoException().isThrownBy(() -> definition(List.of(startStep(), message)).checkInvariants());
    }

    @Test
    void shouldFailWhenStepHasItselfAsPrecondition() {
        var steps = List.of(startStep(), step("step-1", "step-1", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1")
                .hasMessageContaining("precondition");
    }

    @Test
    void shouldFailWhenStepHasItselfAsCompensation() {
        var steps = List.of(startStep(), step("step-1", "start", "step-1"));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1")
                .hasMessageContaining("compensation");
    }

    @Test
    void shouldFailWhenStepsFormAPreconditionCycle() {
        var steps = List.of(
                startStep(),
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
                startStep(),
                step("step-1", "step-3", null),
                step("step-2", "step-1", null),
                step("step-3", "step-2", null)
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void shouldFailWhenStepsFormACycleThroughPluralPreconditions() {
        // The DFS must follow every edge of preconditionStepIds, not just the singular field.
        var steps = List.of(
                startStep(),
                step("a", "start", null),
                step("join-1", List.of("a", "join-2")),
                step("join-2", List.of("join-1"))
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void shouldPassWhenPreconditionsFormATreeWithoutCycles() {
        var steps = List.of(
                startStep(),
                step("a", "start", null),
                step("b", "start", null),
                step("c", "a", null)
        );
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldPassWhenJoinDeclaresSeveralPreconditions() {
        var steps = List.of(
                startStep(),
                step("a", "start", null),
                step("b", "start", null),
                step("join", List.of("a", "b"))
        );
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldFailOnFirstViolatingStep() {
        var steps = List.of(
                startStep(),
                step("step-1", "start", null),
                step("step-2", "step-2", null)   // self-precondition
        );
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-2");
    }

    // ── Roots rule: every flow must enter through a START (or WAIT_FOR_MESSAGE) ──

    @Test
    void shouldFailWhenARootStepIsNotStartNorWaitForMessage() {
        var steps = List.of(step("step-1", (String) null, null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1")
                .hasMessageContaining("every flow must enter through one");
    }

    @Test
    void shouldFailWhenStartStepHasPreconditions() {
        var badStart = new Step("start", "wd-1", StepType.START, "Start", null,
                "step-1", null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
        var steps = List.of(badStart, step("step-1", "start", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("START step 'start' cannot have preconditions");
    }

    @Test
    void shouldFailWhenPreconditionReferencesUnknownStep() {
        var steps = List.of(startStep(), step("step-1", "nope", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown precondition step 'nope'");
    }

    // ── PROCESS steps ──

    @Test
    void shouldPassWhenProcessStepReferencesAnotherWorkflow() {
        var steps = List.of(startStep(), processStep("spawn", "start", "child-wd"));
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    @Test
    void shouldFailWhenProcessStepHasNoChildWorkflowDefinitionId() {
        var steps = List.of(startStep(), processStep("spawn", "start", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spawn")
                .hasMessageContaining("childWorkflowDefinitionId");
    }

    @Test
    void shouldFailWhenProcessStepReferencesItsOwnWorkflow() {
        var steps = List.of(startStep(), processStep("spawn", "start", "wd-1"));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spawn")
                .hasMessageContaining("cannot start this workflow itself as its child");
    }

    private Step endStep(String id, String preconditionStepId) {
        return new Step(id, "wd-1", StepType.END, "End " + id, null,
                preconditionStepId, null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
    }

    @Test
    void shouldFailWhenMoreThanOneStart() {
        var secondStart = new Step("start-2", "wd-1", StepType.START, "Start 2", null,
                null, null, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
        var steps = List.of(startStep(), secondStart, step("a", "start", null));
        assertThatThrownBy(() -> definition(steps).checkInvariants())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most one START");
    }

    @Test
    void shouldPassWithMultipleEndSteps() {
        var steps = List.of(
                startStep(),
                step("a", "start", null),
                endStep("end-1", "a"),
                endStep("end-2", "a"));
        assertThatNoException().isThrownBy(() -> definition(steps).checkInvariants());
    }

    private Step gateway(String id, StepType type, List<String> preconditionStepIds) {
        return new Step(id, "wd-1", type, id, null,
                null, preconditionStepIds, null, false, null, null, null, null, null, 0, null, null, null, null,
                0, 0, false, null, 0, null);
    }

    @Test
    void warnsAboutAFanOutThatIsNotAFork() {
        var steps = List.of(startStep(), step("a", "start", null), step("b", "start", null));
        assertThat(definition(steps).topologyWarnings())
                .anySatisfy(w -> assertThat(w).contains("'start'").contains("outgoing").contains("FORK"));
    }

    @Test
    void warnsAboutAMergeThatIsNotAJoin() {
        var steps = List.of(startStep(), step("a", "start", null), step("b", "start", null),
                step("merge", List.of("a", "b")));
        assertThat(definition(steps).topologyWarnings())
                .anySatisfy(w -> assertThat(w).contains("'merge'").contains("incoming").contains("JOIN"));
    }

    @Test
    void doesNotWarnAboutCompensationAnchors() {
        // 'start' points at both the real step and its false-guarded compensation anchor.
        var steps = List.of(startStep(), step("charge", "start", "refund"), step("refund", "start", null),
                endStep("end", "charge"));
        assertThat(definition(steps).topologyWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnWhenForkAndJoinAreUsed() {
        var steps = List.of(
                startStep(),
                gateway("fork", StepType.FORK, List.of("start")),
                step("a", "fork", null),
                step("b", "fork", null),
                gateway("join", StepType.JOIN, List.of("a", "b")),
                endStep("end", "join"));
        assertThat(definition(steps).topologyWarnings()).isEmpty();
    }
}
