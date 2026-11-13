package io.mateu.workflow.infra.in.monitoring;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryStepExecutionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the process graph is sent to draw an inline PROCESS body as lanes: each iteration's forEach
 * label, and how the body's steps stand in the child run of each iteration (or of the one run).
 */
class ProcessGraphProjectionInlineBodyTest {

    private static final String BODY = """
            [{"id":"start","type":"START","name":"Start"},
             {"id":"create","type":"ACTION","name":"Create","topic":"create-booking","preconditionStepId":"start"},
             {"id":"confirm","type":"ACTION","name":"Confirm","topic":"confirm-booking","preconditionStepId":"create"},
             {"id":"end","type":"END","name":"End","preconditionStepId":"confirm"}]""";
    private static final String ROOMS = """
            {"id":"rooms","type":"PROCESS","name":"Book one room",
             "forEach":{"items":"rooms","as":"room","label":"'Hab. ' + room.number"},"steps":""" + BODY + "}";
    private static final String SINGLE = """
            {"id":"invoice","type":"PROCESS","name":"Invoice","steps":""" + BODY + "}";
    private static final String NAMED = """
            {"id":"named","type":"PROCESS","name":"Named","childWorkflowDefinitionId":"other"}""";

    private static WorkflowDefinition definition(String... steps) {
        return pojoFromJson("{\"id\":\"booking\",\"name\":\"Booking\",\"version\":1,\"steps\":["
                + String.join(",", steps) + "]}", WorkflowDefinition.class);
    }

    private static Process process() {
        return Process.builder().id("p-1").workflowDefinitionId("booking").variables(new ArrayList<>()).build();
    }

    private static StepExecution.StepExecutionBuilder exec(String id, String processId, String stepId, String stepJson,
                                                           StepExecutionStatus status) {
        return StepExecution.builder().id(id).processId(processId).workflowDefinitionId("booking")
                .stepId(stepId).stepJson(stepJson).status(status).variables(List.of());
    }

    /** A store that counts how it is asked, so a test can tell one batch from one call per child. */
    static class CountingStore extends InMemoryStepExecutionRepository {
        int batches;
        int singles;
        List<String> askedFor = List.of();

        @Override
        public Map<String, List<StepExecution>> findByProcessIds(Collection<String> processIds) {
            batches++;
            askedFor = List.copyOf(processIds);
            var result = new HashMap<String, List<StepExecution>>();
            for (var id : processIds) {
                var found = super.findByProcessId(id);
                if (!found.isEmpty()) result.put(id, found);
            }
            return result;
        }

        @Override
        public List<StepExecution> findByProcessId(String processId) {
            singles++;
            return super.findByProcessId(processId);
        }
    }

    /** Child "child-N" of iteration N: create completed, confirm in the given state. */
    private static void child(CountingStore store, int n, StepExecutionStatus confirm) {
        var child = "child-" + n;
        store.save(exec(child + "-s", child, "start", "{}", StepExecutionStatus.COMPLETED).order(1).build());
        store.save(exec(child + "-c", child, "create", "{}", StepExecutionStatus.COMPLETED).order(2).build());
        store.save(exec(child + "-f", child, "confirm", "{}", confirm).order(3).attemptCount(1).build());
    }

    private static List<StepExecution> threeRooms() {
        return List.of(
                exec("c", "p-1", "rooms", ROOMS, StepExecutionStatus.RUNNING).scopeJson("{\"total\":\"3\"}").build(),
                exec("i0", "p-1", "rooms", ROOMS, StepExecutionStatus.COMPLETED).parentExecutionId("c").itemIndex(0).itemLabel("Hab. 101").build(),
                exec("i1", "p-1", "rooms", ROOMS, StepExecutionStatus.ERROR).parentExecutionId("c").itemIndex(1).itemLabel("Hab. 102").build(),
                exec("i2", "p-1", "rooms", ROOMS, StepExecutionStatus.RUNNING).parentExecutionId("c").itemIndex(2).build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> overlay, String stepId) {
        var entry = (Map<String, Object>) overlay.get(stepId);
        return (List<Map<String, Object>>) ((Map<String, Object>) entry.get("iterations")).get("items");
    }

    @Test
    void eachIterationCarriesItsLabelAndHowTheBodyStandsInItsRun() {
        var store = new CountingStore();
        child(store, 0, StepExecutionStatus.COMPLETED);
        child(store, 1, StepExecutionStatus.ERROR);
        child(store, 2, StepExecutionStatus.RUNNING);
        var steps = threeRooms();
        var children = Map.of("i0", "child-0", "i1", "child-1", "i2", "child-2");
        var def = definition(ROOMS);

        var bodies = ProcessGraphProjection.childBodyStates(def, steps, children, store);
        var graph = ProcessGraphProjection.project(def, process(), steps, List.of(), children, bodies);

        var items = items(graph.overlay(), "rooms");
        assertThat(items.get(0)).containsEntry("itemLabel", "Hab. 101")
                .containsEntry("body", Map.of("start", "COMPLETED", "create", "COMPLETED", "confirm", "COMPLETED"));
        assertThat(items.get(1)).containsEntry("itemLabel", "Hab. 102")
                .containsEntry("body", Map.of("start", "COMPLETED", "create", "COMPLETED", "confirm", "ERROR"));
        // No label evaluated for it: the graph falls back to "item 2".
        assertThat(items.get(2)).doesNotContainKey("itemLabel")
                .containsEntry("body", Map.of("start", "COMPLETED", "create", "COMPLETED", "confirm", "RUNNING"));
        // One batch for the three child runs, never one call per child.
        assertThat(store.batches).isEqualTo(1);
        assertThat(store.askedFor).containsExactlyInAnyOrder("child-0", "child-1", "child-2");
    }

    @Test
    void aPlainInlineBodyCarriesItsOneRun() {
        var store = new CountingStore();
        child(store, 7, StepExecutionStatus.PENDING);
        var steps = List.of(exec("se", "p-1", "invoice", SINGLE, StepExecutionStatus.RUNNING).build());
        var children = Map.of("se", "child-7");
        var def = definition(SINGLE);

        var graph = ProcessGraphProjection.project(def, process(), steps, List.of(), children,
                ProcessGraphProjection.childBodyStates(def, steps, children, store));

        assertThat(graph.overlay().get("invoice")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("childProcessId", "child-7")
                .containsEntry("body", Map.of("start", "COMPLETED", "create", "COMPLETED", "confirm", "PENDING"));
    }

    @Test
    void onlyTheFirstLanesAreRead() {
        var store = new CountingStore();
        var steps = new ArrayList<StepExecution>();
        var children = new HashMap<String, String>();
        steps.add(exec("c", "p-1", "rooms", ROOMS, StepExecutionStatus.RUNNING).scopeJson("{\"total\":\"60\"}").build());
        for (int i = 59; i >= 0; i--) {
            steps.add(exec("i" + i, "p-1", "rooms", ROOMS, StepExecutionStatus.COMPLETED).parentExecutionId("c").itemIndex(i).build());
            children.put("i" + i, "child-" + i);
            child(store, i, StepExecutionStatus.COMPLETED);
        }

        var bodies = ProcessGraphProjection.childBodyStates(definition(ROOMS), steps, children, store);

        assertThat(store.batches).isEqualTo(1);
        assertThat(store.askedFor).hasSize(ProcessGraphProjection.MAX_BODY_LANES)
                .contains("child-0", "child-19").doesNotContain("child-20");
        assertThat(bodies).hasSize(ProcessGraphProjection.MAX_BODY_LANES);
    }

    @Test
    void aNamedChildWorkflowIsNotRead() {
        var store = new CountingStore();
        var steps = List.of(exec("se", "p-1", "named", NAMED, StepExecutionStatus.RUNNING).build());

        var bodies = ProcessGraphProjection.childBodyStates(definition(NAMED), steps, Map.of("se", "child-x"), store);

        assertThat(bodies).isEmpty();
        assertThat(store.batches).isZero();
        assertThat(store.singles).isZero();
    }

    @Test
    void theDefaultBatchGroupsByProcessAndSkipsProcessesWithoutSteps() {
        var store = new InMemoryStepExecutionRepository();
        store.save(exec("a1", "a", "x", "{}", StepExecutionStatus.COMPLETED).order(1).build());
        store.save(exec("a2", "a", "y", "{}", StepExecutionStatus.RUNNING).order(2).build());
        store.save(exec("b1", "b", "x", "{}", StepExecutionStatus.COMPLETED).order(1).build());

        var found = store.findByProcessIds(List.of("a", "b", "none"));

        assertThat(found).containsOnlyKeys("a", "b");
        assertThat(found.get("a")).extracting(StepExecution::id).containsExactly("a1", "a2");
    }
}
