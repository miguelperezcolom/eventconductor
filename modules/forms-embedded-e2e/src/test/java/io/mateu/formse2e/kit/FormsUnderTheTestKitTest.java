package io.mateu.formse2e.kit;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;

/**
 * The workflow-test kit with forms-engine on the classpath — as an application that uses forms has
 * it. The context used not to start (no {@code StreamBridge} for the forms engine), and a USER_TASK
 * is still the test's to answer with {@code completeUserTask}, not the forms engine's.
 */
@EventConductorTest
class FormsUnderTheTestKitTest {

    @Test
    void aUserTaskIsAnsweredByTheTest(WorkflowTestRuntime runtime) {
        var process = runtime.start("approval", Map.of("amount", 1500));

        runtime.completeUserTask(process, "approve", Map.of("decision", "APPROVE"));

        assertThat(process).isCompleted().hasVariable("decision", "APPROVE");
    }
}
