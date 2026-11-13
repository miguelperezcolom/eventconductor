package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** forEach label: each iteration named from its item, once, and never failed by its name. */
@EventConductorTest
class ForEachLabelTest {

    private static List<StepExecution> iterations(TestProcess process, String stepId) {
        return process.stepExecutions(stepId).stream().filter(StepExecution::isIteration)
                .sorted(Comparator.comparing(StepExecution::getItemIndex)).toList();
    }

    @Test
    void eachIterationIsNamedFromItsItemAndALabelThatFailsLeavesItUnnamed(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-labelled").thenComplete();

        var run = runtime.start("rooms-labelled", "labels-1",
                Map.of("rooms", List.of(Map.of("number", "101"), Map.of("number", "102"))));

        assertThat(run).isCompleted();
        var named = iterations(run, "reserve");
        assertThat(named).extracting(StepExecution::getItemLabel).containsExactly("Hab. 101", "Hab. 102");
        assertThat(ProcessGraphProjection.rowName(named.get(1))).isEqualTo("reserve · Hab. 102");

        // The broken label did not fail anything: the iterations ran, unnamed, and read "item N".
        var unnamed = iterations(run, "broken");
        assertThat(unnamed).hasSize(2).allSatisfy(i -> assertThat(i.getItemLabel()).isNull());
        assertThat(ProcessGraphProjection.rowName(unnamed.get(0))).isEqualTo("broken · item 0");
    }
}
