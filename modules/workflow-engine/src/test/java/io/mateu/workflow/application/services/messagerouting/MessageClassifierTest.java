package io.mateu.workflow.application.services.messagerouting;

import static org.assertj.core.api.Assertions.assertThat;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageClassifierTest {

    /** A WAIT_FOR_MESSAGE step for {@code messageName}, correlating by {@code expression} (null = default). */
    private static Step waitStep(String messageName, String expression) {
        return new Step("s1", "wd", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false,
                null, null, null, null, null, 0, null, messageName, expression, null, 0, 0, false,
                null, 0, null);
    }

    private static Step actionStep() {
        return new Step("a", "wd", StepType.ACTION, "Do", null, null, null, null, false, "t", null,
                null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static WorkflowDefinition def(String id, List<Step> steps) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, steps);
    }

    @Test
    void a_name_all_of_whose_waiters_use_the_default_correlation_is_business_key() {
        var map = MessageClassifier.classify(List.of(
                def("a", List.of(waitStep("orderPaid", null))),
                def("b", List.of(waitStep("orderPaid", null), actionStep()))));

        assertThat(map).containsEntry("orderPaid", MessageClassification.BUSINESS_KEY);
    }

    @Test
    void a_name_with_any_expression_waiter_is_expression() {
        var map = MessageClassifier.classify(List.of(
                def("a", List.of(waitStep("shipmentArrived", null))),
                def("b", List.of(waitStep("shipmentArrived", "trackingId")))));

        // one default + one expression → EXPRESSION wins (a name is BUSINESS_KEY only if all agree)
        assertThat(map).containsEntry("shipmentArrived", MessageClassification.EXPRESSION);
    }

    @Test
    void several_versions_are_all_considered() {
        var map = MessageClassifier.classify(List.of(
                def("order@1", List.of(waitStep("paid", null))),
                def("order@2", List.of(waitStep("paid", "invoiceId")))));

        assertThat(map).containsEntry("paid", MessageClassification.EXPRESSION);
    }

    @Test
    void a_name_no_step_waits_for_is_absent_from_the_map_and_classifies_as_unknown() {
        var map = MessageClassifier.classify(List.of(def("a", List.of(actionStep()))));
        assertThat(map).doesNotContainKey("ghost");
    }
}
