package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A stubbed model answer is checked against the step's responseSchema, as a real model's is. */
@EventConductorTest
class LlmSchemaStubTest {

    @Test
    void anAnswerTheSchemaTakesGoesThrough(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenAnswer("{\"category\": \"noise\", \"urgent\": true}");
        runtime.whenLlmCall("summary").thenAnswer("Loud party next door.");

        var run = runtime.start("triage-strict", "schema-1", Map.of("complaint", "the party next door"));

        assertThat(run).isCompleted().hasVariable("category", "noise").hasVariable("summary", "Loud party next door.");
        assertThat(runtime.stubProblems()).isEmpty();
    }

    @Test
    void aStubTheSchemaRefusesFailsTheTestNamingTheStub(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenAnswer("{\"category\": \"party\"}");      // not in the enum, no urgent

        var run = runtime.start("triage-strict", "schema-2", Map.of("complaint", "the party next door"));

        assertThat(run).isFailed().hasStepStatus("triage", StepExecutionStatus.ERROR);
        assertThat(runtime.invocationsOf("triage")).hasSize(1);              // not retried: the stub is wrong
        assertThat(runtime.stubProblems()).singleElement().asString()
                .contains("LLM_CALL step 'triage': the stubbed answer does not match the step's responseSchema")
                .contains("urgent")
                .contains("thenInvalidAnswer");
        assertThatThrownBy(() -> assertThat(run).isCompleted()).hasMessageContaining("Stub problems:");
        runtime.takeStubProblems();                                          // provoked on purpose
    }

    @Test
    void anAnswerThatIsNotJsonIsRefusedToo(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenAnswer("noise, I think");

        var run = runtime.start("triage-strict", "schema-3", Map.of("complaint", "loud"));

        assertThat(run).isFailed();
        assertThat(runtime.takeStubProblems()).singleElement().asString().contains("not a JSON document");
    }

    @Test
    void anInvalidModelAnswerIsRetriedAsInProduction(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenInvalidAnswer("{\"category\": \"party\"}")
                .thenAnswer("{\"category\": \"other\", \"urgent\": false}");
        runtime.whenLlmCall("summary").thenAnswer("ok");

        var run = runtime.start("triage-strict", "schema-4", Map.of("complaint", "loud"));
        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(run).isCompleted().hasVariable("category", "other");
        assertThat(runtime.invocationsOf("triage")).hasSize(2);
        assertThat(runtime.stubProblems()).isEmpty();
    }

    @Test
    void anInvalidAnswerNeedsASchemaToBeInvalidAgainst(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenAnswer("{\"category\": \"noise\", \"urgent\": false}");
        runtime.whenLlmCall("summary").thenInvalidAnswer("whatever");

        var run = runtime.start("triage-strict", "schema-5", Map.of("complaint", "loud"));

        assertThat(run).isFailed().hasStepStatus("summary", StepExecutionStatus.ERROR);
        assertThat(runtime.takeStubProblems()).singleElement().asString().contains("has no responseSchema");
    }
}
