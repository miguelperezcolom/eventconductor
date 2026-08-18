package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A step-level {@code preconditionExpression} has one home: {@link Step#resolvedPreconditions()}
 * folds it into the step's links, text and meaning both, so everything downstream reads one kind
 * of condition.
 */
class StepPreconditionFoldTest {

    private Step step(List<String> preconditionStepIds, List<Precondition> preconditions, String expression) {
        return new Step("s", "wd-1", StepType.ACTION, "s", null, null, preconditionStepIds,
                preconditions, expression, false, "topic", null, null, null, null, 0, null, null,
                null, null, 0, 0, false, null, null, 0, null, List.of(), List.of());
    }

    @Test
    void aStepGuardBecomesTheGuardOfALinkThatHadNone() {
        var links = step(List.of("a"), null, "status == 'vip'").resolvedPreconditions();

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.stepId()).isEqualTo("a");
            assertThat(link.expression()).isEqualTo("status == 'vip'");
            // What a step-level expression has always meant: not this way, carry on.
            assertThat(link.onFalse()).isEqualTo(GuardMode.DISCARD);
            assertThat(link.holdsWhenFalse()).isFalse();
        });
    }

    @Test
    void aStepGuardIsAndedOntoEveryLinkAndBothSidesAreBracketed() {
        var links = step(null, List.of(new Precondition("a", "tier == 'gold' || vip")), "amount > 100")
                .resolvedPreconditions();

        assertThat(links).singleElement().satisfies(link -> {
            // Unbracketed, the || would swallow the step guard.
            assertThat(link.expression()).isEqualTo("(amount > 100) && (tier == 'gold' || vip)");
            // The link's own guard was written as something to wait for, and waiting wins.
            assertThat(link.onFalse()).isEqualTo(GuardMode.WAIT);
        });
    }

    @Test
    void aStepWithNoStepGuardKeepsItsLinksExactlyAsWritten() {
        var declared = new Precondition("a", "amount > 100");

        assertThat(step(null, List.of(declared), null).resolvedPreconditions()).containsExactly(declared);
        assertThat(step(null, List.of(declared), "   ").resolvedPreconditions()).containsExactly(declared);
    }

    @Test
    void aStepWithNoLinksHasNothingToFoldInto() {
        assertThat(step(null, null, "status == 'vip'").resolvedPreconditions()).isEmpty();
    }

    @Test
    void aLinkWrittenWithoutAModeWaits() {
        assertThat(new Precondition("a", "amount > 100").onFalse()).isEqualTo(GuardMode.WAIT);
        assertThat(new Precondition("a", "amount > 100", null).onFalse()).isEqualTo(GuardMode.WAIT);
        assertThat(new Precondition("a", "amount > 100").holdsWhenFalse()).isTrue();
        assertThat(new Precondition("a", null).holdsWhenFalse())
                .as("no guard, nothing to be false")
                .isFalse();
    }

    @Test
    void theModeIsNotWrittenIntoDefinitions() {
        // DISCARD only ever appears on a folded link, which is computed and never written; the
        // definition schema does not allow the property, so serializing the default would put an
        // illegal property into every exported workflow.
        var json = JsonSerializer.toJson(step(null, List.of(new Precondition("a", "amount > 100")), null));

        assertThat(json).contains("\"stepId\"").doesNotContain("onFalse");
    }

    @Test
    void aStoredStepWithoutTheModeStillReadsBack() {
        var json = "{\"id\":\"s\",\"type\":\"ACTION\",\"name\":\"s\","
                + "\"preconditions\":[{\"stepId\":\"a\",\"expression\":\"amount > 100\"}]}";

        var step = io.mateu.core.infra.JsonSerializer.pojoFromJson(json, Step.class);

        assertThat(step.resolvedPreconditions()).singleElement()
                .satisfies(link -> assertThat(link.onFalse()).isEqualTo(GuardMode.WAIT));
    }
}
