package io.mateu.workflow.infra.in.monitoring;

import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the process graph is sent so it can expand a forEach into its iterations and open the child
 * run of a PROCESS step on a double click.
 */
class ProcessGraphProjectionIterationsTest {

    private static final String FOR_EACH_PROCESS = """
            {"id":"book","type":"PROCESS","name":"Book room","childWorkflowDefinitionId":"room",
             "forEach":{"items":"rooms","as":"room"}}""";
    private static final String PLAIN_PROCESS = """
            {"id":"invoice","type":"PROCESS","name":"Invoice","childWorkflowDefinitionId":"invoicing"}""";
    private static final String ACTION = """
            {"id":"notify","type":"ACTION","name":"Notify","topic":"t"}""";

    private static WorkflowDefinition definition(String... steps) {
        return pojoFromJson("{\"id\":\"booking\",\"name\":\"Booking\",\"version\":1,\"steps\":["
                + String.join(",", steps) + "]}", WorkflowDefinition.class);
    }

    private static Process process() {
        return Process.builder().id("p-1").workflowDefinitionId("booking").variables(new ArrayList<>()).build();
    }

    private static StepExecution.StepExecutionBuilder exec(String id, String stepId, String stepJson, StepExecutionStatus status) {
        return StepExecution.builder().id(id).processId("p-1").workflowDefinitionId("booking")
                .stepId(stepId).stepJson(stepJson).status(status).variables(List.of());
    }

    private static List<StepExecution> forEachOverThreeRooms() {
        return List.of(
                exec("c", "book", FOR_EACH_PROCESS, StepExecutionStatus.RUNNING).scopeJson("{\"total\":\"3\"}").build(),
                exec("i0", "book", FOR_EACH_PROCESS, StepExecutionStatus.COMPLETED).parentExecutionId("c").itemIndex(0).attemptCount(0).build(),
                exec("i1", "book", FOR_EACH_PROCESS, StepExecutionStatus.ERROR).parentExecutionId("c").itemIndex(1).attemptCount(2).build(),
                exec("i2", "book", FOR_EACH_PROCESS, StepExecutionStatus.CREATED).parentExecutionId("c").itemIndex(2).build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> entry) {
        return (List<Map<String, Object>>) ((Map<String, Object>) entry.get("iterations")).get("items");
    }

    @Test
    void aForEachListsItsIterationsInItemOrderWithStateAndTheChildEachStarted() {
        var steps = forEachOverThreeRooms();
        var error = LogMessage.builder().id("l-1").processId("p-1").stepExecutionId("i1")
                .timestamp(LocalDateTime.parse("2026-11-13T10:00:00"))
                .messageType(MessageType.Error.name()).message("room 102 is not available").build();

        var graph = ProcessGraphProjection.project(definition(FOR_EACH_PROCESS), process(), steps, List.of(error),
                Map.of("i0", "child-0", "i1", "child-1"));

        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) graph.overlay().get("book");
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) entry.get("iterations");
        assertThat(summary).containsEntry("total", 3).containsEntry("completed", 1L).containsEntry("failed", 1L);
        var items = items(entry);
        assertThat(items).extracting(i -> i.get("index")).containsExactly(0, 1, 2);
        assertThat(items.get(0)).containsEntry("state", "COMPLETED").containsEntry("childProcessId", "child-0");
        assertThat(items.get(1)).containsEntry("state", "ERROR").containsEntry("childProcessId", "child-1")
                .containsEntry("error", "room 102 is not available").containsEntry("attempt", 2);
        // Not reached yet: no state (drawn as not started), and no child to open.
        assertThat(items.get(2)).doesNotContainKey("state").doesNotContainKey("childProcessId");
        // The container itself started nothing: its iterations did.
        assertThat(entry).doesNotContainKey("childProcessId");
    }

    @Test
    void aPlainProcessStepCarriesItsChildRun() {
        var steps = List.of(exec("se-inv", "invoice", PLAIN_PROCESS, StepExecutionStatus.PENDING).build());

        var graph = ProcessGraphProjection.project(definition(PLAIN_PROCESS), process(), steps, List.of(),
                Map.of("se-inv", "child-inv"));

        assertThat(graph.overlay().get("invoice")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("childProcessId", "child-inv")
                .doesNotContainKey("iterations");
    }

    @Test
    void theOldOverloadStillProjectsWithoutChildren() {
        var graph = ProcessGraphProjection.project(definition(FOR_EACH_PROCESS), process(), forEachOverThreeRooms(), List.of());

        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) graph.overlay().get("book");
        assertThat(items(entry)).hasSize(3).allSatisfy(i -> assertThat(i).doesNotContainKey("childProcessId"));
    }

    @Test
    void aBigForEachListsOnlyTheFirstHundredButCountsThemAll() {
        var steps = new ArrayList<StepExecution>();
        steps.add(exec("c", "book", FOR_EACH_PROCESS, StepExecutionStatus.RUNNING).scopeJson("{\"total\":\"250\"}").build());
        for (int i = 249; i >= 0; i--) {
            steps.add(exec("i" + i, "book", FOR_EACH_PROCESS, StepExecutionStatus.COMPLETED).parentExecutionId("c").itemIndex(i).build());
        }

        var graph = ProcessGraphProjection.project(definition(FOR_EACH_PROCESS), process(), steps, List.of(), Map.of());

        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) graph.overlay().get("book");
        assertThat(((Map<?, ?>) entry.get("iterations")).get("total")).isEqualTo(250);
        assertThat(items(entry)).hasSize(ProcessGraphProjection.MAX_LISTED_ITERATIONS)
                .first().satisfies(first -> assertThat(first).containsEntry("index", 0));
    }

    @Test
    void anItemWithSeveralLiveExecutionsShowsTheMostTellingOne() {
        var steps = List.of(
                exec("c", "book", FOR_EACH_PROCESS, StepExecutionStatus.RUNNING).scopeJson("{\"total\":\"1\"}").build(),
                exec("i0a", "book", FOR_EACH_PROCESS, StepExecutionStatus.COMPLETED).parentExecutionId("c").itemIndex(0).build(),
                exec("i0b", "book", FOR_EACH_PROCESS, StepExecutionStatus.RUNNING).parentExecutionId("c").itemIndex(0).build());

        var graph = ProcessGraphProjection.project(definition(FOR_EACH_PROCESS), process(), steps, List.of(), Map.of("i0b", "child-b"));

        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) graph.overlay().get("book");
        assertThat(items(entry)).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("state", "RUNNING").containsEntry("childProcessId", "child-b"));
    }

    @Test
    void childLookupAsksOnlyForTheExecutionsOfProcessSteps() {
        var store = new InMemoryProcessRepositoryForTests() {
            List<String> askedFor;

            @Override
            public Map<String, String> findChildIdsByParentStepExecutionIds(java.util.Collection<String> ids) {
                askedFor = List.copyOf(ids);
                return super.findChildIdsByParentStepExecutionIds(ids);
            }
        };
        var steps = new ArrayList<>(forEachOverThreeRooms());
        steps.add(exec("n", "notify", ACTION, StepExecutionStatus.COMPLETED).build());

        ProcessGraphProjection.childProcessIds(definition(FOR_EACH_PROCESS, ACTION), steps, store);

        assertThat(store.askedFor).containsExactlyInAnyOrder("c", "i0", "i1", "i2");
    }

    @Test
    void childLookupDoesNotTouchTheStoreWithoutAProcessStep() {
        var store = new InMemoryProcessRepositoryForTests() {
            boolean asked;

            @Override
            public Map<String, String> findChildIdsByParentStepExecutionIds(java.util.Collection<String> ids) {
                asked = true;
                return Map.of();
            }
        };

        var found = ProcessGraphProjection.childProcessIds(definition(ACTION),
                List.of(exec("n", "notify", ACTION, StepExecutionStatus.COMPLETED).build()), store);

        assertThat(found).isEmpty();
        assertThat(store.asked).isFalse();
    }

    @Test
    void theInMemoryStoreMapsEachParentExecutionToItsLatestChild() {
        var store = new InMemoryProcessRepositoryForTests();
        store.put(Process.builder().id("old").parentStepExecutionId("i0").created(LocalDateTime.parse("2026-11-13T09:00:00")).variables(new ArrayList<>()).build());
        store.put(Process.builder().id("new").parentStepExecutionId("i0").created(LocalDateTime.parse("2026-11-13T09:05:00")).variables(new ArrayList<>()).build());
        store.put(Process.builder().id("other").parentStepExecutionId("i9").created(LocalDateTime.parse("2026-11-13T09:01:00")).variables(new ArrayList<>()).build());
        store.put(Process.builder().id("root").variables(new ArrayList<>()).build());

        assertThat(store.findChildIdsByParentStepExecutionIds(List.of("i0", "i1")))
                .containsExactlyEntriesOf(Map.of("i0", "new"));
        assertThat(store.findChildIdsByParentStepExecutionIds(List.of())).isEmpty();
    }
}
