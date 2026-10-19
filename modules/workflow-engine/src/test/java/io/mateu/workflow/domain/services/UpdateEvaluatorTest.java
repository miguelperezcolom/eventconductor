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

    @Test
    void sensitiveFieldsAreMaskedInTheRecordedPayload() {
        var handler = new UpdateDefinition("pay", null, null, null, null, null, null, null, null, List.of("card"));

        assertThat(UpdateEvaluator.masked(handler, "{\"card\":\"4111\",\"amount\":3}"))
                .isEqualTo("{\"card\":\"****\",\"amount\":3}");
        assertThat(UpdateEvaluator.masked(handler, "[1]")).isEqualTo("\"****\"");
        assertThat(UpdateEvaluator.masked(handler, "not json")).isEqualTo("\"****\"");
        assertThat(UpdateEvaluator.masked(update(null, null, null, null), "{\"card\":\"4111\"}"))
                .isEqualTo("{\"card\":\"4111\"}");
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
