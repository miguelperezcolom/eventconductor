package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemovedStepTypesTest {

    @Test
    void agentCallIsRemovedAndPointsAtTheAgentStep() {
        assertThat(RemovedStepTypes.isRemoved("AGENT_CALL")).isTrue();
        assertThat(RemovedStepTypes.message("AGENT_CALL")).hasValue(
                "AGENT_CALL was removed in 2.34.0; use the AGENT step (agents from the IA catalogue) — see "
                        + RemovedStepTypes.AGENT_GUIDE);
        assertThat(RemovedStepTypes.violation("triage", "AGENT_CALL")).get().asString()
                .startsWith("Step 'triage': AGENT_CALL was removed in 2.34.0");
    }

    @Test
    void otherTypesAreNotRemoved() {
        assertThat(RemovedStepTypes.isRemoved("LLM_CALL")).isFalse();
        assertThat(RemovedStepTypes.isRemoved(null)).isFalse();
        assertThat(RemovedStepTypes.message("ACTION")).isEmpty();
        assertThat(RemovedStepTypes.message(null)).isEmpty();
        assertThat(RemovedStepTypes.violation("a", "ACTION")).isEmpty();
    }
}
