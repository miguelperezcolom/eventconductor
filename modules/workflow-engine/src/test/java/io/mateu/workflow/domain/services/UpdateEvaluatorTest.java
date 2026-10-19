package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.UpdateDefinition;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateEvaluatorTest {

    private static Process process(Variable... variables) {
        return Process.builder().id("p-1").businessKey("order-1").workflowDefinitionId("orders")
                .status(ProcessStatus.RUNNING).variables(new ArrayList<>(List.of(variables))).build();
    }

    private static UpdateDefinition update(List<UpdateDefinition.Validation> validate, Map<String, String> set,
                                           String reply, Object replyTemplate) {
        return new UpdateDefinition("changeAddress", null, null, null, validate, set, null, reply, replyTemplate, null);
    }

    private static Map<String, String> set(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void setSeesTheStateBeforeAndTheReplyTheStateAfter() {
        var outcome = UpdateEvaluator.evaluate(
                update(null, set("previous", "address", "address", "payload.address"),
                        "{'address': address, 'previous': previous}", null),
                process(new Variable("address", "Old street 1")), UpdateEvaluator.payloadOf("{\"address\":\"New street 2\"}"), 0);

        assertThat(outcome).isInstanceOf(UpdateEvaluator.Applied.class);
        var applied = (UpdateEvaluator.Applied) outcome;
        assertThat(applied.variables()).containsExactly(new Variable("previous", "Old street 1"),
                new Variable("address", "New street 2"));
        assertThat(applied.result()).contains("\"address\":\"New street 2\"").contains("\"previous\":\"Old street 1\"");
    }

    @Test
    void aGuardThatDoesNotHoldRefusesWithItsMessage() {
        var outcome = UpdateEvaluator.evaluate(
                update(List.of(new UpdateDefinition.Validation("payload.address != address", "Same address.")),
                        set("address", "payload.address"), null, null),
                process(new Variable("address", "A")), UpdateEvaluator.payloadOf("{\"address\":\"A\"}"), 0);

        assertThat(outcome).isEqualTo(new UpdateEvaluator.Refused("Same address."));
    }

    @Test
    void aGuardThatCannotBeEvaluatedRefusesWithAGenericMessage() {
        var outcome = UpdateEvaluator.evaluate(
                update(List.of(new UpdateDefinition.Validation("payload.amount > 10", null)), null, null, null),
                process(), UpdateEvaluator.payloadOf("{\"amount\":\"lots\"}"), 0);

        assertThat(outcome).isInstanceOf(UpdateEvaluator.Refused.class);
        assertThat(((UpdateEvaluator.Refused) outcome).message()).contains("payload.amount > 10");
    }

    @Test
    void aSetThatThrowsFailsTheUpdateAndWritesNothing() {
        var outcome = UpdateEvaluator.evaluate(update(null, set("ok", "1", "boom", "1 / 0"), null, null),
                process(), null, 0);

        assertThat(outcome).isInstanceOf(UpdateEvaluator.Failed.class);
        assertThat(((UpdateEvaluator.Failed) outcome).message()).startsWith("set.boom");
    }

    @Test
    void valuesThatAreNotTextAreStoredAsJson() {
        var outcome = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(
                update(null, set("lines", "payload.lines", "count", "payload.lines.size()", "total", "payload.total"), null, null),
                process(), UpdateEvaluator.payloadOf("{\"lines\":[1,2],\"total\":12.5}"), 0);

        assertThat(outcome.variables()).containsExactly(new Variable("lines", "[1,2]"), new Variable("count", "2"),
                new Variable("total", "12.5"));
        assertThat(outcome.result()).isEqualTo("{}");
    }

    @Test
    void aReplyTemplateSeesThePayloadAndTheNewVariables() {
        var outcome = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(
                update(null, set("note", "payload.note"), null, Map.of("note", "${note}", "by", "${payload.by}")),
                process(), UpdateEvaluator.payloadOf("{\"note\":\"fragile\",\"by\":\"ana\"}"), 0);

        assertThat(outcome.result()).contains("\"note\":\"fragile\"").contains("\"by\":\"ana\"");
    }

    @Test
    void aReplyOverTheLimitFails() {
        var outcome = UpdateEvaluator.evaluate(update(null, null, "'0123456789'", null), process(), null, 5);

        assertThat(outcome).isInstanceOf(UpdateEvaluator.Failed.class);
        assertThat(((UpdateEvaluator.Failed) outcome).message()).contains("max-reply-bytes");
    }

    @Test
    void aReplyThatThrowsFails() {
        var outcome = UpdateEvaluator.evaluate(update(null, null, "1 / 0", null), process(), null, 0);

        assertThat(outcome).isInstanceOf(UpdateEvaluator.Failed.class);
    }

    static io.mateu.workflow.crypto.VariableEncryption encryption() {
        var key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return new io.mateu.workflow.crypto.VariableEncryption(
                new io.mateu.workflow.crypto.AesGcmVariableCodec("k1", Map.of("k1", key)));
    }

    static final java.util.function.Predicate<String> CARD = name -> name.equals("cardNumber") || name.equals("pin");

    @Test
    void theFieldsASetWritesIntoASensitiveVariableAreProtected() {
        var handler = new UpdateDefinition("pay", null, null, null, null,
                set("cardNumber", "payload.card", "pin", "payload['code']", "amount", "payload.amount"),
                null, null, null, List.of("cvv"));

        assertThat(UpdateEvaluator.protectedFields(handler, CARD, List.of("card", "code", "amount", "cvv", "pin")))
                .containsExactlyInAnyOrder("cvv", "pin", "card", "code");
        assertThat(UpdateEvaluator.protectedFields(handler, null, List.of("card"))).containsExactly("cvv");
        assertThat(UpdateEvaluator.protectedFields(null, CARD, List.of("card"))).isEmpty();
    }

    @Test
    void protectedFieldsAreSealedOpenedBackAndShownMasked() {
        var encryption = encryption();
        var handler = new UpdateDefinition("pay", null, null, null, null, set("cardNumber", "payload.card"),
                null, null, null, null);
        var payload = "{\"card\":\"4111111111111111\",\"amount\":12,\"meta\":{\"a\":1}}";

        var sealed = UpdateEvaluator.seal(handler, payload, CARD, encryption);

        assertThat(sealed).doesNotContain("4111111111111111").contains("ec:enc:v1:k1:").contains("\"amount\":12");
        assertThat(UpdateEvaluator.open(sealed, encryption)).isEqualTo(payload);
        assertThat(UpdateEvaluator.shown(sealed)).isEqualTo("{\"card\":\"••••\",\"amount\":12,\"meta\":{\"a\":1}}");
        // Without keys the record cannot carry them: masked.
        assertThat(UpdateEvaluator.seal(handler, payload, CARD, io.mateu.workflow.crypto.VariableEncryption.DISABLED))
                .contains("\"card\":\"••••\"").doesNotContain("4111");
        // Nothing protected: as it came.
        assertThat(UpdateEvaluator.seal(handler, payload, null, encryption)).isEqualTo(payload);
        assertThat(UpdateEvaluator.open(payload, encryption)).isEqualTo(payload);
        assertThat(UpdateEvaluator.shown(payload)).isEqualTo(payload);
        assertThat(UpdateEvaluator.seal(null, payload, CARD, encryption)).isEqualTo(payload);
    }

    @Test
    void aPayloadThatIsNotAnObjectIsSealedWholeWhenAnythingIsSensitive() {
        var encryption = encryption();
        var declared = new UpdateDefinition("pay", null, null, null, null, null, null, null, null, List.of("x"));

        var sealed = UpdateEvaluator.seal(declared, "[1,2]", null, encryption);

        assertThat(sealed).startsWith("\"ec:enc:v1:");
        assertThat(UpdateEvaluator.open(sealed, encryption)).isEqualTo("[1,2]");
        assertThat(UpdateEvaluator.shown(sealed)).isEqualTo("\"••••\"");
        assertThat(UpdateEvaluator.seal(declared, "[1,2]", null, io.mateu.workflow.crypto.VariableEncryption.DISABLED))
                .isEqualTo("\"••••\"");
        assertThat(UpdateEvaluator.seal(declared, "not json", null, encryption)).isEqualTo("\"••••\"");
        assertThat(UpdateEvaluator.seal(update(null, null, null, null), "[1]", CARD, encryption)).isEqualTo("[1]");
        assertThat(UpdateEvaluator.shown("[\"" + encryption.seal("x") + "\", {\"y\": [\"" + encryption.seal("z") + "\"]}]"))
                .isEqualTo("[\"••••\",{\"y\":[\"••••\"]}]");
    }

    @Test
    void sensitiveVariablesAreKeptOutOfGuardsAndRepliesUnlessTheUpdateOptsIn() {
        var process = process(new Variable("cardNumber", "4111"), new Variable("amount", "3"));
        var hidden = new UpdateDefinition("peek", null, null, null,
                List.of(new UpdateDefinition.Validation("cardNumber == null", null)),
                set("last4", "cardNumber.substring(2)"), null, "{'card': cardNumber, 'last4': last4}", null, null);
        var allowed = new UpdateDefinition("peek", null, null, null, null, null, null, "cardNumber", null, null, true);

        var outcome = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(hidden, process, null, 0, CARD);
        // set reads it (it stays inside the engine); the guard and the reply do not see it.
        assertThat(outcome.variables()).containsExactly(new Variable("last4", "11"));
        assertThat(outcome.result()).contains("\"card\":null").contains("\"last4\":\"11\"");
        assertThat(((UpdateEvaluator.Applied) UpdateEvaluator.evaluate(allowed, process, null, 0, CARD)).result())
                .isEqualTo("\"4111\"");
        assertThat(allowed.allowsSensitiveVariables()).isTrue();
        assertThat(hidden.allowsSensitiveVariables()).isFalse();
    }

    @Test
    void theBusinessKeyIsInScope() {
        var outcome = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(update(null, null, "businessKey", null),
                process(), null, 0);

        assertThat(outcome.result()).isEqualTo("\"order-1\"");
    }

    @Test
    void aCounterKeptAsTextCountsAsTheGuideShows() {
        var counter = update(null, set("changes", "changes == null ? 1 : changes * 1 + 1"), "changes", null);

        var first = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(counter, process(), null, 0);
        var second = (UpdateEvaluator.Applied) UpdateEvaluator.evaluate(counter, process(new Variable("changes", "1")), null, 0);

        assertThat(first.variables()).containsExactly(new Variable("changes", "1"));
        assertThat(second.variables()).containsExactly(new Variable("changes", "2"));
        assertThat(second.result()).isEqualTo("\"2\"");
    }
}
