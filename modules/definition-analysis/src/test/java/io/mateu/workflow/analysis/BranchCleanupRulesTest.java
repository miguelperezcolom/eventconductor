package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BranchCleanupRulesTest {

    @Test
    void compensateLosersBelongsOnAnXorJoin() {
        assertThat(BranchCleanupRules.stepViolations("JOIN", "XOR", true, null, "step 'first'")).isEmpty();
        assertThat(BranchCleanupRules.stepViolations("JOIN", "AND", true, null, "step 'all'"))
                .singleElement().asString().contains("step 'all'", "compensateLosers", "XOR");
        assertThat(BranchCleanupRules.stepViolations("JOIN", null, true, null, "step 'all'")).hasSize(1);
        assertThat(BranchCleanupRules.stepViolations("ACTION", null, true, null, "step 'a'")).hasSize(1);
    }

    @Test
    void compensateAbandonedBelongsOnAnEnd() {
        assertThat(BranchCleanupRules.stepViolations("END", null, null, true, "step 'done'")).isEmpty();
        assertThat(BranchCleanupRules.stepViolations("JOIN", "XOR", null, true, "step 'first'"))
                .singleElement().asString().contains("compensateAbandoned", "END");
    }

    @Test
    void absentOrFalseIsAlwaysFine() {
        assertThat(BranchCleanupRules.stepViolations("ACTION", null, null, null, "step 'a'")).isEmpty();
        assertThat(BranchCleanupRules.stepViolations("ACTION", null, false, false, "step 'a'")).isEmpty();
    }
}
