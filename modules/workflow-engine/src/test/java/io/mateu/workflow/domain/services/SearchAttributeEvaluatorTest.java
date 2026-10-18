package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A process's search attributes, computed from the definition snapshot it runs and its variables. */
class SearchAttributeEvaluatorTest {

    static Process process(String definitionJson, int version, Variable... variables) {
        return Process.builder().id("p-" + version).workflowDefinitionId("orders").workflowDefinitionVersion(version)
                .workflowDefinitionJson(definitionJson).businessKey("bk-1").variables(List.of(variables)).build();
    }

    static final String DEFINITION = """
            {"id":"orders","name":"Orders","searchAttributes":{
              "customerId":"${customerId}",
              "amount":{"value":"${total}","type":"number"},
              "placedOn":{"value":"${placedOn}","type":"date"},
              "label":{"value":"order ${businessKey}","type":"text"},
              "vip":{"value":"${total > 100}","type":"bool"},
              "broken":"${customerId.nope()}"},"steps":[]}""";

    @Test
    void evaluatesEveryDeclaredAttributeAgainstTheVariables() {
        var values = SearchAttributeEvaluator.evaluate(process(DEFINITION, 1, new Variable("customerId", "C-9"),
                new Variable("total", "150"), new Variable("placedOn", "2026-10-18")));
        assertThat(values).extracting(SearchAttributeValue::name)
                .containsExactly("customerId", "amount", "placedOn", "label", "vip");
        assertThat(values.get(0).keyword()).isEqualTo("C-9");
        assertThat(values.get(1).number()).isEqualTo(150.0);
        assertThat(values.get(2).type()).isEqualTo(SearchAttributeType.DATE);
        assertThat(values.get(3).keyword()).isEqualTo("order bk-1");
        assertThat(values.get(4).keyword()).isEqualTo("true");
        assertThat(SearchAttributeEvaluator.declares(process(DEFINITION, 1))).isTrue();
    }

    @Test
    void whatCannotBeIndexedIsLeftOutNeverFailingTheProcess() {
        var values = SearchAttributeEvaluator.evaluate(process(DEFINITION, 2, new Variable("total", "lots")));
        // No customerId (undefined), no number, no date: the text remains.
        assertThat(values).extracting(SearchAttributeValue::name).contains("label")
                .doesNotContain("customerId", "amount", "placedOn", "broken");
    }

    @Test
    void aDefinitionWithoutAttributesCostsNothingAndIndexesNothing() {
        assertThat(SearchAttributeEvaluator.evaluate(process("{\"id\":\"orders\",\"steps\":[]}", 3))).isEmpty();
        assertThat(SearchAttributeEvaluator.evaluate(process(null, 4))).isEmpty();
        assertThat(SearchAttributeEvaluator.evaluate(null)).isEmpty();
        assertThat(SearchAttributeEvaluator.specsOf(process("{\"searchAttributes\": 3}", 5))).isEmpty();
        assertThat(SearchAttributeEvaluator.specsOf(process("{\"searchAttributes\": {\"9x\": \"${a}\"}}", 6))).isEmpty();
        assertThat(SearchAttributeEvaluator.specsOf(process("not json \"searchAttributes\"", 7))).isEmpty();
        assertThat(SearchAttributeEvaluator.declares(process("{}", 8))).isFalse();
    }
}
