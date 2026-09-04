package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.data.Pageable;
import org.junit.jupiter.api.Test;
import java.util.Collections;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionTest {

    private Step step(String id) {
        return new Step(id, "wd-1", StepType.ACTION, "Step " + id, null, null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private WorkflowDefinition definition(List<Step> steps) {
        return new WorkflowDefinition("wd-1", "Test", 1, "desc",  false, 0, false, null, 0, steps);
    }

    @Test
    void toStringReturnsNameWhenIdSet() {
        var wd = definition(List.of());
        assertThat(wd.toString()).isEqualTo("Test");
    }

    @Test
    void toStringReturnsNewLabelWhenIdNull() {
        var wd = new WorkflowDefinition(null, "Test", 1, "desc",  false, 0, false, null, 0, List.of());
        assertThat(wd.toString()).isEqualTo("New workflow definition");
    }

    @Test
    void searchableTextCombinesNameAndDescription() {
        var wd = definition(List.of());
        assertThat(wd.searchableText()).contains("Test").contains("desc");
    }

    @Test
    void stepsReturnsEmptyListWhenNull() {
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",  false, 0, false, null, 0, null);
        assertThat(wd.steps()).isEmpty();
    }

    @Test
    void maxConcurrentExecutionsReturnsConfiguredValueWhenLimitEnabled() {
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",  true, 5, false, null, 0, List.of());
        assertThat(wd.maxConcurrentExecutions()).isEqualTo(5);
    }

    @Test
    void maxConcurrentExecutionsReturnsOneWhenLimitDisabled() {
        var wd = definition(List.of());
        assertThat(wd.maxConcurrentExecutions()).isEqualTo(1);
    }

    @Test
    void checkInvariantsPassesWhenNullSteps() {
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",  false, 0, false, null, 0, null);
        wd.checkInvariants();
    }

    /**
     * Every copy has to carry the arrangement. Each `with*` here rebuilds the record through the
     * canonical constructor, so a component that is not threaded through one of them is dropped
     * without a word — which is exactly how `requiredScopes` and `requiredRoles` came to be lost
     * on the paths that copy through a narrower constructor.
     */
    @Test
    void everyCopyKeepsTheArrangement() {
        var layout = Map.of("s1", new NodePosition(60, 160));
        var wd = definition(List.of(step("s1"))).withLayout(layout);

        assertThat(wd.withSteps(List.of(step("s2"))).layout()).isEqualTo(layout);
        assertThat(wd.withMaxSteps(7).layout()).isEqualTo(layout);
        assertThat(wd.withPaused(true).layout()).isEqualTo(layout);
        assertThat(wd.withVersion(9).layout()).isEqualTo(layout);
        assertThat(wd.withDeclaredStatus(WorkflowStatus.DISABLED).layout()).isEqualTo(layout);
        assertThat(wd.withRuntimeStatus(WorkflowStatus.DISABLED).layout()).isEqualTo(layout);
        assertThat(wd.withRuntimeStateOf(wd).layout()).isEqualTo(layout);
    }

    /**
     * Null, not an empty map. The two mean different things to the exporter — only null is omitted
     * — and defaulting it would put a `layout: {}` line into every definition ever written.
     */
    @Test
    void aDefinitionNobodyArrangedHasNoLayoutAtAll() {
        assertThat(definition(List.of(step("s1"))).layout()).isNull();
    }

    @Test
    void searchReturnsStepOptions() {
        var steps = List.of(step("s1"), step("s2"));
        var wd = definition(steps);
        var result = wd.search(null, null, new Pageable(0, 100, Collections.emptyList()), null);
        assertThat(result.page().content()).hasSize(2);
        assertThat(result.page().content().get(0).value()).isEqualTo("s1");
    }
}
