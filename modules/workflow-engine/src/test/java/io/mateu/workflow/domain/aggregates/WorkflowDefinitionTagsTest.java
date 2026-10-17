package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tags travel with every copy of a definition: pausing, enabling, re-importing (runtime state carried
 * over), resolving task references (steps replaced) and versioning all go through a copy method, and
 * one that forgot the tags erased them from the stored definition.
 */
class WorkflowDefinitionTagsTest {

    private static final WorkflowDefinition TAGGED =
            new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, List.of())
                    .withTags(List.of("billing", "team:payments"));

    @Test
    void everyCopyKeepsTheTags() {
        List<UnaryOperator<WorkflowDefinition>> copies = List.of(
                d -> d.withPaused(true),
                d -> d.withRuntimeStatus(WorkflowStatus.DISABLED),
                d -> d.withDeclaredStatus(WorkflowStatus.ARCHIVED),
                d -> d.withDisabled(true),
                d -> d.withArchived(true),
                d -> d.withRuntimeStateOf(TAGGED),
                d -> d.withSteps(List.of()),
                d -> d.withMaxSteps(7),
                d -> d.withLayout(Map.of()),
                d -> d.withProcessLock(null),
                d -> d.withSyncInvocation(null),
                d -> d.withSchedules(List.of()),
                d -> d.withTriggers(List.of()),
                d -> d.withVersion(2));
        for (var copy : copies) {
            assertThat(copy.apply(TAGGED).tags()).containsExactly("billing", "team:payments");
        }
    }
}
