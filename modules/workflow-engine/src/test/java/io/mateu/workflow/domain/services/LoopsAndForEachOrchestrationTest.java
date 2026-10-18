package io.mateu.workflow.domain.services;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orchestrator's container pass (forEach) and loop pass (repeat, loopBack), driven step by step
 * over an in-memory list of executions — the way the step-over drives it, follow-up passes included.
 */
class LoopsAndForEachOrchestrationTest {

    private final WorkflowOrchestrationService service = new WorkflowOrchestrationService();
    private final List<StepExecution> store = new ArrayList<>();
    private Process process;

    private void definition(String json, Variable... variables) {
        var definition = JsonSerializer.pojoFromJson(json, WorkflowDefinition.class);
        definition.checkInvariants();
        process = Process.builder().id("p").status(ProcessStatus.RUNNING).businessKey("bk")
                .workflowDefinitionJson(json).variables(new ArrayList<>(List.of(variables))).build();
        int order = 1;
        for (Step step : definition.steps()) {
            store.add(StepExecution.create(step, "p", order++));
        }
    }

    /** Step-overs until nothing moves — what the status events would trigger in the engine. */
    private WorkflowOrchestrationService.TransitionResult stepOver() {
        WorkflowOrchestrationService.TransitionResult result;
        int guard = 0;
        do {
            result = service.calculateNextTransitions(process, List.copyOf(store));
            process = result.getUpdatedProcess();
            for (var se : result.getStepsToSave()) {
                store.removeIf(e -> e.getId().equals(se.getId()));
                store.add(se);
            }
        } while (!result.getStepsToSave().isEmpty() && guard++ < 20);
        return result;
    }

    private List<StepExecution> pending(String stepId) {
        return store.stream().filter(e -> stepId.equals(e.getStepId()) && e.getStatus() == StepExecutionStatus.PENDING && !e.isContainer())
                .sorted(Comparator.comparing(e -> e.getItemIndex() == null ? 0 : e.getItemIndex())).toList();
    }

    private StepExecution current(String stepId) {
        return StepExecutions.currentOf(stepId, store);
    }

    private List<StepExecution> iterations(String stepId) {
        return StepExecutions.iterationsOf(current(stepId), store);
    }

    /** A worker's answer: an iteration keeps what it reports, any other step writes the process. */
    private void report(StepExecution execution, StepExecutionStatus status, Variable... variables) {
        if (execution.isIteration()) {
            execution.recordOutput(List.of(variables));
        } else {
            process.updateVariables(List.of(variables));
        }
        execution.updateStatus(status, status == StepExecutionStatus.ERROR);
        stepOver();
    }

    private String variable(String name) {
        return process.getVariables().stream().filter(v -> name.equals(v.name())).map(Variable::value).findFirst().orElse(null);
    }

    private static final String ROOMS = """
            {"id":"rooms","name":"Rooms","version":1,"steps":[
              {"id":"start","type":"START","name":"Start"},
              {"id":"reserve","type":"ACTION","name":"Reserve","topic":"t","preconditionStepId":"start",
               "forEach":{"items":"rooms","as":"room","output":"res","outputElement":"room + ':' + id","maxConcurrency":2}},
              {"id":"end","type":"END","name":"End","preconditionStepId":"reserve"}]}""";

    @Test
    void aContainerMaterialisesItsIterationsAndStartsThemUpToItsConcurrency() {
        definition(ROOMS, new Variable("rooms", "[\"a\",\"b\",\"c\"]"));
        stepOver();

        assertThat(current("reserve").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(current("reserve").isContainer()).isTrue();
        assertThat(current("reserve").totalIterations()).isEqualTo(3);
        assertThat(current("reserve").getDeadlineAt()).isNull();
        assertThat(iterations("reserve")).hasSize(3);
        assertThat(pending("reserve")).hasSize(2);
        assertThat(pending("reserve").get(0).getVariables()).contains(new Variable("room", "a"), new Variable("index", "0"));

        report(pending("reserve").get(1), StepExecutionStatus.COMPLETED, new Variable("id", "2"));
        assertThat(pending("reserve")).hasSize(2);
        report(pending("reserve").get(0), StepExecutionStatus.COMPLETED, new Variable("id", "1"));
        report(pending("reserve").get(0), StepExecutionStatus.COMPLETED, new Variable("id", "3"));

        assertThat(current("reserve").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(variable("res")).isEqualTo("[\"a:1\",\"b:2\",\"c:3\"]");
        assertThat(variable("id")).isNull();
        assertThat(current("end").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void analyticsCountTheIterationsAsRunsAndNotTheContainer() {
        definition(ROOMS, new Variable("rooms", "[\"a\",\"b\"]"));
        stepOver();

        assertThat(current("reserve").isRunOfItsStep()).isFalse();
        assertThat(iterations("reserve")).allMatch(StepExecution::isRunOfItsStep);
        assertThat(current("start").isRunOfItsStep()).isTrue();
        // The definition graph counts a process once per step, however many of its executions are live.
        var live = store.stream().filter(e -> "reserve".equals(e.getStepId())).toList();
        assertThat(io.mateu.workflow.infra.in.ui.pages.WorkflowGraphOverlays.countsByStep(live)).containsEntry("reserve", 1);
        assertThat(io.mateu.workflow.infra.in.ui.pages.WorkflowGraphOverlays.heatByStep(live).get("reserve")[0]).isEqualTo(1);
    }

    @Test
    void aFailedIterationFailsTheContainerAndCancelsTheRest() {
        definition(ROOMS, new Variable("rooms", "[\"a\",\"b\",\"c\"]"));
        stepOver();

        report(pending("reserve").get(0), StepExecutionStatus.ERROR);

        assertThat(current("reserve").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(iterations("reserve")).extracting(StepExecution::getStatus)
                .containsExactly(StepExecutionStatus.ERROR, StepExecutionStatus.CANCELLED, StepExecutionStatus.CANCELLED);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void itemsThatAreNotAListFailTheStep() {
        definition(ROOMS, new Variable("rooms", "not a list"));
        stepOver();

        assertThat(current("reserve").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void aMissingListIsAnEmptyOne() {
        definition(ROOMS);
        stepOver();

        assertThat(current("reserve").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(variable("res")).isEqualTo("[]");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void anEndOnAnotherBranchCancelsTheContainerAndItsIterations() {
        definition("""
                {"id":"race","name":"Race","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"fork","type":"FORK","name":"Fork","preconditionStepId":"start"},
                  {"id":"each","type":"ACTION","name":"Each","topic":"t","preconditionStepId":"fork","forEach":{"items":"[1,2]"}},
                  {"id":"fast","type":"ACTION","name":"Fast","topic":"t","preconditionStepId":"fork"},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"fast"}]}""");
        stepOver();
        report(pending("fast").get(0), StepExecutionStatus.COMPLETED);

        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(store.stream().filter(e -> "each".equals(e.getStepId())))
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED));
    }

    @Test
    void aRepeatParksItsNextRunForTheDelay() {
        definition("""
                {"id":"poll","name":"Poll","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"poll","type":"ACTION","name":"Poll","topic":"t","preconditionStepId":"start",
                   "repeat":{"until":"iteration >= 2","maxIterations":3,"delay":"PT1H"}},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"poll"}]}""");
        stepOver();
        report(pending("poll").get(0), StepExecutionStatus.COMPLETED);

        var next = current("poll");
        assertThat(next.getIteration()).isEqualTo(1);
        assertThat(next.getStatus()).isEqualTo(StepExecutionStatus.AWAITING_RETRY);
        assertThat(next.getDeadlineAt()).isAfter(java.time.LocalDateTime.now().plusMinutes(59));
        assertThat(next.getAttemptCount()).isZero();

        next.releaseForRetry();
        stepOver();
        report(pending("poll").get(0), StepExecutionStatus.COMPLETED);
        assertThat(current("poll").getLoopDecision()).isEqualTo(StepExecution.LOOP_EXITED);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aLoopDecisionLatches() {
        definition("""
                {"id":"latch","name":"Latch","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"a","type":"ACTION","name":"A","topic":"t","preconditionStepId":"start",
                   "loopBack":{"to":"start","while":"again == 'yes'","maxIterations":5}},
                  {"id":"b","type":"ACTION","name":"B","topic":"t","preconditionStepId":"a"},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"b"}]}""",
                new Variable("again", "no"));
        stepOver();
        report(pending("a").get(0), StepExecutionStatus.COMPLETED);
        assertThat(current("a").getLoopDecision()).isEqualTo(StepExecution.LOOP_EXITED);

        // Flipping the variable afterwards does not re-open the loop that already exited.
        process.updateVariables(List.of(new Variable("again", "yes")));
        stepOver();
        assertThat(store.stream().filter(e -> "a".equals(e.getStepId()))).hasSize(1);
        assertThat(pending("b")).hasSize(1);
    }

    @Test
    void theEnginesHardCapBoundsEveryLoop() {
        service.setMaxLoopIterations(2);
        definition("""
                {"id":"hard","name":"Hard","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"a","type":"ACTION","name":"A","topic":"t","preconditionStepId":"start",
                   "repeat":{"until":"false","maxIterations":50}},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"a"}]}""");
        stepOver();
        report(pending("a").get(0), StepExecutionStatus.COMPLETED);
        report(pending("a").get(0), StepExecutionStatus.COMPLETED);

        assertThat(current("a").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(current("a").getLoopDecision()).isEqualTo(StepExecution.LOOP_EXHAUSTED);
    }

    @Test
    void aStepsOwnMaxSuccessfulExecutionsCapsALoopBack() {
        definition("""
                {"id":"capped","name":"Capped","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"a","type":"ACTION","name":"A","topic":"t","preconditionStepId":"start","maxSuccessfulExecutions":2},
                  {"id":"b","type":"ACTION","name":"B","topic":"t","preconditionStepId":"a",
                   "loopBack":{"to":"a","maxIterations":10}},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"b"}]}""");
        stepOver();
        report(pending("a").get(0), StepExecutionStatus.COMPLETED);
        report(pending("b").get(0), StepExecutionStatus.COMPLETED);
        report(pending("a").get(0), StepExecutionStatus.COMPLETED);
        report(pending("b").get(0), StepExecutionStatus.COMPLETED);

        assertThat(current("b").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void aLoopBackCancelsWhatIsStillInFlightInsideItsSection() {
        definition("""
                {"id":"inflight","name":"In flight","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"fork","type":"FORK","name":"Fork","preconditionStepId":"start"},
                  {"id":"slow","type":"ACTION","name":"Slow","topic":"t","preconditionStepId":"fork"},
                  {"id":"quick","type":"ACTION","name":"Quick","topic":"t","preconditionStepId":"fork",
                   "loopBack":{"to":"fork","while":"go == 'yes'","maxIterations":2}},
                  {"id":"join","type":"JOIN","name":"Join","preconditionStepIds":["slow","quick"]},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"join"}]}""",
                new Variable("go", "yes"));
        stepOver();
        var slow = pending("slow").get(0);
        report(pending("quick").get(0), StepExecutionStatus.COMPLETED);

        // The section is fork → quick; slow is not on a path to quick, so it carries on.
        assertThat(slow.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(current("fork").getIteration()).isEqualTo(1);
        assertThat(current("quick").getIteration()).isEqualTo(1);
    }

    @Test
    void multiInstanceHelpersReadStructureFromVariables() {
        var forEach = new io.mateu.workflow.domain.aggregates.ForEach("order.lines", null, null, null, null, null, null, null);
        var p = Process.builder().id("x").variables(List.of(new Variable("order", "{\"lines\":[1,2]}"))).build();
        assertThat(MultiInstance.items(forEach, p)).containsExactly(1, 2);
        assertThat(MultiInstance.asVariableValue(java.util.Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(MultiInstance.asVariableValue(null)).isNull();
        assertThat(MultiInstance.structured("[1]")).isEqualTo(List.of(1));
        assertThat(MultiInstance.structured("{broken")).isEqualTo("{broken");
        assertThat(MultiInstance.stringMap("not json")).isEmpty();
        assertThat(MultiInstance.overlay(List.of(new Variable("item", "old"), new Variable("x", "1")),
                java.util.Map.of("item", "new"))).containsExactlyInAnyOrder(new Variable("x", "1"), new Variable("item", "new"));
        var array = new io.mateu.workflow.domain.aggregates.ForEach("'[3,4]'", null, null, "sequential", null, null, null, "any");
        assertThat(MultiInstance.items(array, p)).containsExactly(3, 4);
        assertThat(array.concurrency()).isEqualTo(1);
        assertThat(array.completesOnAny()).isTrue();
        assertThat(array.completionCondition()).isNull();
        var broken = new io.mateu.workflow.domain.aggregates.ForEach("'[3,'", null, null, null, null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MultiInstance.items(broken, p))
                .isInstanceOf(MultiInstance.ItemsException.class);
    }

    @Test
    void validationRejectsABadLoopAndABadExpression() {
        var notUpstream = """
                {"id":"bad","name":"Bad","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"a","type":"ACTION","name":"A","topic":"t","preconditionStepId":"start","loopBack":{"to":"end","maxIterations":2}},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"a"}]}""";
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        JsonSerializer.pojoFromJson(notUpstream, WorkflowDefinition.class).checkInvariants())
                .hasMessageContaining("not upstream");
        var badExpression = """
                {"id":"bad","name":"Bad","version":1,"steps":[
                  {"id":"start","type":"START","name":"Start"},
                  {"id":"a","type":"ACTION","name":"A","topic":"t","preconditionStepId":"start","repeat":{"until":"x ==","maxIterations":2}},
                  {"id":"end","type":"END","name":"End","preconditionStepId":"a"}]}""";
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        JsonSerializer.pojoFromJson(badExpression, WorkflowDefinition.class).checkInvariants())
                .hasMessageContaining("repeat.until");
    }
}
