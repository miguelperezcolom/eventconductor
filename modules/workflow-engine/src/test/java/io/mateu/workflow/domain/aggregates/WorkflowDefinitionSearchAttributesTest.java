package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The search attributes travel with every copy of a definition, read and write as the file wrote them, and are checked at load. */
class WorkflowDefinitionSearchAttributesTest {

    static final Map<String, Object> DECLARED = new LinkedHashMap<>(Map.of(
            "customerId", "${customerId}",
            "amount", Map.of("value", "${total}", "type", "number")));

    static final WorkflowDefinition INDEXED =
            new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, List.of())
                    .withRetention(ProcessRetention.of("P30D"))
                    .withSearchAttributes(DECLARED);

    @Test
    void everyCopyKeepsThem() {
        List<UnaryOperator<WorkflowDefinition>> copies = List.of(
                d -> d.withPaused(true), d -> d.withRuntimeStatus(WorkflowStatus.DISABLED),
                d -> d.withDeclaredStatus(WorkflowStatus.ARCHIVED), d -> d.withRuntimeStateOf(INDEXED),
                d -> d.withSteps(List.of()), d -> d.withMaxSteps(7), d -> d.withLayout(Map.of()),
                d -> d.withProcessLock(null), d -> d.withSyncInvocation(null), d -> d.withSchedules(List.of()),
                d -> d.withTriggers(List.of()), d -> d.withTags(List.of("x")), d -> d.withRetention(null),
                d -> d.withVersion(2));
        for (var copy : copies) {
            assertThat(copy.apply(INDEXED).searchAttributes()).isEqualTo(DECLARED);
        }
        assertThat(INDEXED.withSearchAttributes(null).retention()).isEqualTo(ProcessRetention.of("P30D"));
        assertThat(INDEXED.searchAttributeDeclarations()).extracting(d -> d.name() + ":" + d.type())
                .containsExactlyInAnyOrder("customerId:keyword", "amount:number");
    }

    @Test
    void readsAndWritesAsTheFileWritesThem() throws Exception {
        var definition = new YAMLMapper().readValue("""
                id: wf
                name: Wf
                searchAttributes:
                  customerId: "${customerId}"
                  amount: {value: "${total}", type: number}
                steps: []
                """, WorkflowDefinition.class);
        assertThat(definition.searchAttributes()).containsKeys("customerId", "amount");
        var json = new ObjectMapper().writeValueAsString(definition);
        assertThat(json).contains("\"searchAttributes\":{\"customerId\":\"${customerId}\",\"amount\":{\"value\":\"${total}\",\"type\":\"number\"}}");
        assertThat(new ObjectMapper().writeValueAsString(INDEXED.withSearchAttributes(null))).doesNotContain("searchAttributes");
    }

    @Test
    void aBadDeclarationOrExpressionIsRefusedAtLoad() {
        var badName = INDEXED.withSearchAttributes(Map.of("9x", "${a}"));
        assertThatThrownBy(badName::checkInvariants).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'9x' is not a valid search attribute name");
        var badExpression = INDEXED.withSearchAttributes(Map.of("amount", "${total +* 2}"));
        assertThatThrownBy(badExpression::checkInvariants).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("searchAttributes.amount");
    }
}
