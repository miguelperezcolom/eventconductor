package io.mateu.workflow.test;

import io.mateu.workflow.application.services.SensitiveVariableMasking;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;

/**
 * The test kit with sensitive variables: a definition that declares them runs once the test configures a
 * key, and the assertions read the plaintext — encryption is a property of storage and transport, never of
 * what a test (or the engine) sees.
 */
@EventConductorTest(properties = "workflow.encryption.keys.kit=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=")
class SensitiveVariablesTest {

    @Test
    void assertionsAndStubsSeeThePlaintext(WorkflowTestRuntime runtime) {
        runtime.whenTask("charge-card").thenComplete(Map.of("authCode", "A-77"));

        var payment = runtime.start("card-payment", "card-1", Map.of("cardNumber", "4111111111111111"));

        assertThat(payment).isCompleted().hasVariable("cardNumber", "4111111111111111").hasVariable("authCode", "A-77");
        org.assertj.core.api.Assertions.assertThat(runtime.invocationsOf("charge")).singleElement()
                .satisfies(call -> org.assertj.core.api.Assertions.assertThat(call.variable("cardNumber"))
                        .isEqualTo("4111111111111111"));
        // What a person would see of it.
        org.assertj.core.api.Assertions.assertThat(SensitiveVariableMasking.asMap(payment.state()))
                .containsEntry("cardNumber", "••••").containsEntry("authCode", "••••");
    }
}
