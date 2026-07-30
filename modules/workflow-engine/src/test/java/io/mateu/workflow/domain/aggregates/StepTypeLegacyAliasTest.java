package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-rename step type {@code MESSAGE} must keep deserializing as
 * {@code WAIT_FOR_MESSAGE}: the stepJson persisted for in-flight processes and old
 * workflow definition files still carry the legacy string.
 */
class StepTypeLegacyAliasTest {

    @Test
    void legacyMessageStepJsonDeserializesAsWaitForMessage() {
        var json = """
                {"id":"wait","workflowDefinitionId":"wd-1","type":"MESSAGE","name":"Wait",
                 "messageName":"payment-received","correlationExpression":"businessKey"}
                """;

        var step = pojoFromJson(json, Step.class);

        assertThat(step.type()).isEqualTo(StepType.WAIT_FOR_MESSAGE);
        assertThat(step.messageName()).isEqualTo("payment-received");
    }

    @Test
    void currentTypeNamesStillDeserialize() {
        var waiting = pojoFromJson("{\"id\":\"w\",\"type\":\"WAIT_FOR_MESSAGE\",\"name\":\"W\"}", Step.class);
        var sending = pojoFromJson("{\"id\":\"s\",\"type\":\"SEND_MESSAGE\",\"name\":\"S\"}", Step.class);

        assertThat(waiting.type()).isEqualTo(StepType.WAIT_FOR_MESSAGE);
        assertThat(sending.type()).isEqualTo(StepType.SEND_MESSAGE);
    }

    @Test
    void sendMessageStepRoundTripsWithItsMessageVariables() {
        var json = """
                {"id":"send","type":"SEND_MESSAGE","name":"Send",
                 "messageName":"payment-received","correlationExpression":"orderId",
                 "messageVariables":["amount","orderId"]}
                """;

        var step = pojoFromJson(json, Step.class);

        assertThat(step.type()).isEqualTo(StepType.SEND_MESSAGE);
        assertThat(step.messageVariables()).containsExactly("amount", "orderId");
    }
}
