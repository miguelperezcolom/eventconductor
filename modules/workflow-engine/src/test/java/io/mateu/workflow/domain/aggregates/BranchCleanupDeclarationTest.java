package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** {@code compensateLosers} / {@code compensateAbandoned} / {@code compensate}: where they may go, and that they never move a version hash. */
class BranchCleanupDeclarationTest {

    private static WorkflowDefinition definition(String extraOnJoin, String extraOnEnd) {
        return pojoFromJson("""
                {"id":"wf","name":"wf","version":1,"steps":[
                  {"id":"start","type":"START"},
                  {"id":"fork","type":"FORK","preconditionStepId":"start"},
                  {"id":"a","type":"ACTION","topic":"t","preconditionStepId":"fork"},
                  {"id":"b","type":"ACTION","topic":"t","preconditionStepId":"fork"},
                  {"id":"first","type":"JOIN","joinType":"XOR","preconditionStepIds":["a","b"]%s},
                  {"id":"end","type":"END","preconditionStepId":"first"%s}
                ]}""".formatted(extraOnJoin, extraOnEnd), WorkflowDefinition.class);
    }

    @Test
    void theFlagsAreAcceptedWhereTheyBelong() {
        assertThatCode(() -> definition(",\"compensateLosers\":true", ",\"compensateAbandoned\":true").checkInvariants())
                .doesNotThrowAnyException();
        var def = definition(",\"compensateLosers\":true", ",\"compensateAbandoned\":true");
        assertThat(def.steps().get(4).compensatesLosers()).isTrue();
        assertThat(def.steps().get(5).compensatesAbandoned()).isTrue();
    }

    @Test
    void compensateLosersOutsideAnXorJoinIsRefused() {
        var def = pojoFromJson("""
                {"id":"wf","name":"wf","version":1,"steps":[
                  {"id":"start","type":"START"},
                  {"id":"a","type":"ACTION","topic":"t","preconditionStepId":"start","compensateLosers":true},
                  {"id":"end","type":"END","preconditionStepId":"a"}
                ]}""", WorkflowDefinition.class);
        assertThatThrownBy(def::checkInvariants).hasMessageContaining("step 'a'").hasMessageContaining("compensateLosers");
    }

    @Test
    void compensateAbandonedOutsideAnEndIsRefused() {
        assertThatThrownBy(() -> definition(",\"compensateAbandoned\":true", "").checkInvariants())
                .hasMessageContaining("step 'first'").hasMessageContaining("compensateAbandoned");
    }

    @Test
    void compensateIsAcceptedOnAnEndAndRefusedElsewhereOrBesideCompensateAbandoned() {
        var def = definition("", ",\"compensate\":true");
        assertThatCode(def::checkInvariants).doesNotThrowAnyException();
        assertThat(def.steps().get(5).compensates()).isTrue();
        assertThat(def.steps().get(4).compensates()).isFalse();
        assertThatThrownBy(() -> definition(",\"compensate\":true", "").checkInvariants())
                .hasMessageContaining("step 'first'").hasMessageContaining("compensate is only allowed on an END");
        assertThatThrownBy(() -> definition("", ",\"compensate\":true,\"compensateAbandoned\":true").checkInvariants())
                .hasMessageContaining("compensate and compensateAbandoned");
    }

    @Test
    void absentOrFalseLeavesTheJsonAsItWas() {
        var without = toJson(definition("", "").steps());
        var withFalse = toJson(definition(",\"compensateLosers\":false", ",\"compensateAbandoned\":false,\"compensate\":false").steps());
        assertThat(without).doesNotContain("compensateLosers").doesNotContain("compensateAbandoned").doesNotContain("\"compensate\"");
        assertThat(withFalse).isEqualTo(without);
    }
}
