package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The manual operations on forEach iterations and containers and on loop passes: an iteration is
 * completed by hand while its container runs it, a container completed by hand stops its iterations, a
 * retry from a forEach discards its iterations, and a loop's superseded pass is history.
 */
class InstanceOperationsLoopsE2eTest extends AbstractE2eTest {

    static final AuthorizationContext ALICE = new AuthorizationContext("alice", List.of(), List.of());
    static final io.mateu.workflow.dtos.Variable TWO_LINES =
            new io.mateu.workflow.dtos.Variable("order", "{\"lines\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}");

    @Autowired ProcessOperationsService operations;
    @Autowired IncidentService incidents;

    @Test
    void anIterationCompletedByHandFeedsItsContainersOutputNotTheProcess() {
        var callbacks = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("line", (request, callback, n) -> callbacks.add(callback));
        createProcess("foreach-sequential", "ops-fe-1", TWO_LINES);
        var first = iterations("ops-fe-1").getFirst();
        assertThat(first.getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        operations.completeStep(process("ops-fe-1").getId(), first.getId(), List.of(new Variable("ok", "manual")),
                "Handled at the counter", ALICE);

        // Sequential: the next item starts once the first is done.
        assertThat(worker.invocationsOf("line")).isEqualTo(2);
        callbacks.get(1).complete(List.of(TestWorker.var("ok", "line1")));
        assertThat(process("ops-fe-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("ops-fe-1", "handled")).isEqualTo("[{\"ok\":\"manual\"},{\"ok\":\"line1\"}]");
        assertThat(variable("ops-fe-1", "ok")).as("an iteration's output is not the process's").isNull();
        assertThat(logsOf("ops-fe-1")).anySatisfy(line -> assertThat(line).contains("(iteration 0 of its forEach)"));
    }

    @Test
    void aContainerCompletedByHandStopsItsIterationsAndALateReplyIsDropped() {
        var requests = new CopyOnWriteArrayList<TaskExecutionRequested>();
        worker.on("line", (request, callback, n) -> requests.add(request));
        createProcess("foreach-sequential", "ops-fe-2", TWO_LINES);
        var container = container("ops-fe-2");

        operations.completeStep(process("ops-fe-2").getId(), container.getId(),
                List.of(new Variable("handled", "[]")), "Lines handled offline", ALICE);

        assertThat(container("ops-fe-2").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(iterations("ops-fe-2")).extracting(StepExecution::getStatus).containsOnly(StepExecutionStatus.CANCELLED);
        assertThat(process("ops-fe-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("ops-fe-2", "handled")).isEqualTo("[]");

        var first = requests.getFirst();
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(first.taskExecutionId(), List.of(), "late",
                StepExecutionStatus.ERROR, false, first.attempt()));
        assertThat(process("ops-fe-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aFailedForEachIsOneIncidentAndIsRetriedFromItsContainerOverItsItemsAfresh() {
        worker.on("line", (request, callback, n) -> {
            if (request.variables().stream().anyMatch(v -> "item".equals(v.name()) && v.value().contains("B"))) {
                callback.fail("Line B rejected");
            } else {
                callback.complete(List.of(TestWorker.var("ok", "done")));
            }
        });
        createProcess("foreach-sequential", "ops-fe-3", TWO_LINES);
        org.awaitility.Awaitility.await().untilAsserted(() ->
                assertThat(process("ops-fe-3").getStatus()).isEqualTo(ProcessStatus.ERROR));
        var id = process("ops-fe-3").getId();

        // One incident, on the forEach step — not one per iteration.
        assertThat(incidents.incidents("foreach-sequential").groups()).singleElement().satisfies(group -> {
            assertThat(group.stepId()).isEqualTo("line");
            assertThat(group.failedSteps()).isEqualTo(1);
        });
        // The failed iteration's container has ended: the iteration is not completed by hand.
        var failedIteration = iterations("ops-fe-3").stream()
                .filter(i -> i.getStatus() == StepExecutionStatus.ERROR).findFirst().orElseThrow();
        assertThatThrownBy(() -> operations.completeStep(id, failedIteration.getId(), List.of(), "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("forEach step");

        // Fix the data and run the forEach again: its old iterations are discarded, new ones made.
        worker.on("line", TestWorker.succeed(TestWorker.var("ok", "again")));
        operations.setVariables(id, List.of(new Variable("order", "{\"lines\":[{\"sku\":\"C\"}]}")), List.of(),
                "Line B removed", ALICE);
        operations.retryFromStep(id, "line", "Re-run the lines", ALICE);

        assertThat(process("ops-fe-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var all = steps("ops-fe-3").stream().filter(StepExecution::isIteration).toList();
        assertThat(all.stream().filter(StepExecution::isSuperseded))
                .hasSize(2).allMatch(i -> StepExecution.LOOP_DISCARDED.equals(i.getLoopDecision()));
        assertThat(iterations("ops-fe-3")).singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        assertThat(variable("ops-fe-3", "handled")).isEqualTo("[{\"ok\":\"again\"}]");
    }

    @Test
    void aSupersededPassIsHistoryAndRetryingAPollRunsItsCurrentPassAgain() {
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        worker.on("poll", (request, callback, n) -> callback.complete(List.of(
                TestWorker.var("paymentStatus", polls.incrementAndGet() >= 2 ? "SETTLED" : "PENDING"))));
        worker.on("ship", TestWorker.deferForever());
        createProcess("repeat-poll", "ops-loop-1");
        var passes = passes("ops-loop-1");
        assertThat(passes).hasSize(2);
        assertThat(passes.getFirst().isSuperseded()).isTrue();
        var id = process("ops-loop-1").getId();

        assertThatThrownBy(() -> operations.completeStep(id, passes.getFirst().getId(), List.of(), "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("history");

        operations.retryFromStep(id, "poll", "Re-check the payment", ALICE);

        var after = passes("ops-loop-1");
        assertThat(after).as("no new pass: the current one runs again").hasSize(2);
        assertThat(after.getFirst().isSuperseded()).isTrue();
        assertThat(after.get(1).getLoopDecision()).isEqualTo(StepExecution.LOOP_EXITED);
        assertThat(worker.invocationsOf("poll")).isEqualTo(3);
        assertThat(worker.invocationsOf("ship")).isEqualTo(2);
        assertThat(step("ops-loop-1", "ship").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
    }

    private StepExecution container(String businessKey) {
        return steps(businessKey).stream().filter(s -> "line".equals(s.getStepId()) && !s.isIteration()
                && !s.isSuperseded()).findFirst().orElseThrow();
    }

    private List<StepExecution> iterations(String businessKey) {
        return steps(businessKey).stream().filter(s -> s.isIteration() && !s.isSuperseded())
                .sorted(Comparator.comparing(StepExecution::getItemIndex)).toList();
    }

    private List<StepExecution> passes(String businessKey) {
        return steps(businessKey).stream().filter(s -> "poll".equals(s.getStepId()))
                .sorted(Comparator.comparingInt(StepExecution::getIteration)).toList();
    }

    private String variable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }
}
