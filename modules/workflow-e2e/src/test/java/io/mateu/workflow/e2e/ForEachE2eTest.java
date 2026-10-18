package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.services.WorkflowOrchestrationService;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Multi-instance end to end, in memory: a step with {@code forEach} runs once per item, each
 * iteration its own task, and the container moves the flow on when its completion is reached.
 */
class ForEachE2eTest extends AbstractE2eTest {

    @Autowired WorkflowOrchestrationService orchestration;

    @AfterEach
    void restoreLimits() {
        orchestration.setMaxForEachItems(1000);
    }

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    private List<StepExecution> iterations(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()) && s.isIteration())
                .sorted(Comparator.comparing(StepExecution::getItemIndex))
                .toList();
    }

    private StepExecution container(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()) && !s.isIteration() && !s.isSuperseded())
                .findFirst().orElseThrow();
    }

    @Test
    void eachItemRunsAsItsOwnTaskAndTheOutputIsCollectedInItemOrder() {
        worker.on("reserve", (req, cb, n) -> cb.complete(List.of(
                TestWorker.var("reservationId", "R-" + var(req, "room")),
                TestWorker.var("roomIndex", "clobbered?"))));

        createProcess("foreach-rooms", "fe-1", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        assertThat(process("fe-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("reserve")).isEqualTo(3);
        var tasks = worker.received().stream().filter(r -> "reserve".equals(r.stepId())).toList();
        assertThat(tasks).extracting(r -> var(r, "room")).containsExactlyInAnyOrder("101", "102", "103");
        assertThat(tasks).extracting(r -> var(r, "roomIndex")).containsExactlyInAnyOrder("0", "1", "2");
        // Every iteration is its own task: unique ids, none of them the container's.
        assertThat(tasks).extracting(TaskExecutionRequested::taskExecutionId).doesNotHaveDuplicates()
                .doesNotContain(container("fe-1", "reserve").getId());
        assertThat(processVariable("fe-1", "reservations")).isEqualTo("[\"R-101\",\"R-102\",\"R-103\"]");
        // What iterations report never lands on the process — only `output` does.
        assertThat(processVariable("fe-1", "roomIndex")).isNull();
        assertThat(processVariable("fe-1", "reservationId")).isNull();
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        assertThat(iterations("fe-1", "reserve")).extracting(StepExecution::getStatus)
                .containsOnly(StepExecutionStatus.COMPLETED);
        assertThat(container("fe-1", "reserve").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void anEmptyListCompletesTheStepAtOnce() {
        createProcess("foreach-rooms", "fe-empty", new io.mateu.workflow.dtos.Variable("rooms", "[]"));

        assertThat(process("fe-empty").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("reserve")).isZero();
        assertThat(processVariable("fe-empty", "reservations")).isEqualTo("[]");
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
    }

    @Test
    void sequentialRunsOneAtATimeInListOrderOverAJsonObjectVariable() {
        var deferred = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("line", (req, cb, n) -> deferred.add(cb));

        createProcess("foreach-sequential", "fe-seq",
                new io.mateu.workflow.dtos.Variable("order", "{\"lines\":[{\"sku\":\"A\"},{\"sku\":\"B\"},{\"sku\":\"C\"}]}"));

        for (int i = 0; i < 3; i++) {
            assertThat(worker.invocationsOf("line")).isEqualTo(i + 1);
            var request = worker.received().get(worker.received().size() - 1);
            assertThat(var(request, "item")).isEqualTo("{\"sku\":\"" + (char) ('A' + i) + "\"}");
            assertThat(var(request, "index")).isEqualTo(String.valueOf(i));
            deferred.get(i).complete(List.of(TestWorker.var("ok", "line" + i)));
        }
        assertThat(process("fe-seq").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(processVariable("fe-seq", "handled"))
                .isEqualTo("[{\"ok\":\"line0\"},{\"ok\":\"line1\"},{\"ok\":\"line2\"}]");
    }

    @Test
    void maxConcurrencyBoundsTheIterationsInFlight() {
        var deferred = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("call", (req, cb, n) -> deferred.add(cb));

        createProcess("foreach-throttled", "fe-thr");

        assertThat(worker.invocationsOf("call")).isEqualTo(2);
        deferred.get(0).complete();
        assertThat(worker.invocationsOf("call")).isEqualTo(3);
        deferred.get(1).complete();
        deferred.get(2).complete();
        assertThat(worker.invocationsOf("call")).isEqualTo(5);
        assertThat(iterations("fe-thr", "call").stream()
                .filter(i -> i.getStatus() == StepExecutionStatus.PENDING)).hasSize(2);
        deferred.get(3).complete();
        deferred.get(4).complete();
        assertThat(process("fe-thr").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aCompletionConditionAndAnyEndTheStepEarlyAndCancelTheRest() {
        var quotes = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quote", (req, cb, n) -> quotes.add(cb));
        var firsts = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("first", (req, cb, n) -> firsts.add(cb));

        createProcess("foreach-completion", "fe-cmp", new io.mateu.workflow.dtos.Variable("suppliers", "[\"s1\",\"s2\",\"s3\"]"));

        assertThat(quotes).hasSize(3);
        quotes.get(2).complete(List.of(TestWorker.var("price", "30")));
        assertThat(container("fe-cmp", "quote").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        quotes.get(0).complete(List.of(TestWorker.var("price", "10")));
        // Two completed: the condition holds, the third is cancelled, slots stay aligned with the items.
        assertThat(container("fe-cmp", "quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(iterations("fe-cmp", "quote").get(1).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(processVariable("fe-cmp", "quotes")).isEqualTo("[\"10\",null,\"30\"]");

        assertThat(firsts).hasSize(3);
        firsts.get(1).complete();
        assertThat(container("fe-cmp", "first").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(iterations("fe-cmp", "first")).extracting(StepExecution::getStatus)
                .containsExactly(StepExecutionStatus.CANCELLED, StepExecutionStatus.COMPLETED, StepExecutionStatus.CANCELLED);
        assertThat(process("fe-cmp").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        // A late answer from a cancelled iteration changes nothing.
        firsts.get(0).complete();
        assertThat(iterations("fe-cmp", "first").get(0).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void anIterationThatFailsAfterItsRetriesFailsTheStepAndTheCompletedOnesAreCompensated() {
        worker.on("reserve", (req, cb, n) -> {
            if ("102".equals(var(req, "room"))) {
                cb.fail("no availability");
            } else {
                cb.complete(List.of(TestWorker.var("reservationId", "R-" + var(req, "room"))));
            }
        });
        var released = new CopyOnWriteArrayList<String>();
        worker.on("release", (req, cb, n) -> {
            released.add(var(req, "room") + "/" + var(req, "reservationId"));
            cb.complete();
        });

        createProcess("foreach-rooms", "fe-fail", new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        // 102 ran twice (retries: 1, after a backoff) and failed for good; the step failed, never confirming.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("fe-fail").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(iterations("fe-fail", "reserve").get(1).getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(iterations("fe-fail", "reserve").get(1).getAttemptCount()).isEqualTo(1);
        assertThat(container("fe-fail", "reserve").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(worker.invocationsOf("confirm")).isZero();
        // The completed iterations are undone, one compensation run each, seeing what each reported.
        assertThat(released).containsExactlyInAnyOrder("101/R-101", "103/R-103");
        assertThat(process("fe-fail").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }

    @Test
    void cancellingTheProcessCancelsTheIterationsInFlight() {
        worker.on("reserve", TestWorker.deferForever());

        var process = createProcess("foreach-rooms", "fe-cancel", new io.mateu.workflow.dtos.Variable("rooms", "[\"1\",\"2\"]"));
        assertThat(iterations("fe-cancel", "reserve")).extracting(StepExecution::getStatus)
                .containsOnly(StepExecutionStatus.PENDING);

        cancelProcessUseCase.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(process.getId()));

        assertThat(process("fe-cancel").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(iterations("fe-cancel", "reserve")).extracting(StepExecution::getStatus)
                .containsOnly(StepExecutionStatus.CANCELLED);
        assertThat(container("fe-cancel", "reserve").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void aListLongerThanTheLimitFailsTheStepAtStart() {
        orchestration.setMaxForEachItems(2);

        createProcess("foreach-rooms", "fe-cap", new io.mateu.workflow.dtos.Variable("rooms", "[\"1\",\"2\",\"3\"]"));

        assertThat(worker.invocationsOf("reserve")).isZero();
        assertThat(container("fe-cap", "reserve").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("fe-cap").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("fe-cap")).anySatisfy(e -> assertThat(e).contains("workflow.foreach.max-items"));
    }

    @Test
    void retryingAFailedForEachRerunsOnlyTheFailedIterations() {
        var room2Calls = new java.util.concurrent.atomic.AtomicInteger();
        worker.on("reserve", (req, cb, n) -> {
            if ("2".equals(var(req, "room")) && room2Calls.incrementAndGet() <= 2) {
                cb.fail();
            } else {
                cb.complete();
            }
        });
        worker.on("release", TestWorker.deferForever());

        createProcess("foreach-rooms", "fe-retry", new io.mateu.workflow.dtos.Variable("rooms", "[\"1\",\"2\"]"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(container("fe-retry", "reserve").getStatus()).isEqualTo(StepExecutionStatus.ERROR));
        int before = worker.invocationsOf("reserve");

        var failed = iterations("fe-retry", "reserve").get(1);
        retryStepExecutionUseCase.handle(new io.mateu.workflow.application.usecases.stepexecution.retry
                .RetryStepExecutionCommand(failed.getId()));

        // Only the failed item ran again; the completed one stands.
        assertThat(worker.invocationsOf("reserve")).isEqualTo(before + 1);
        assertThat(container("fe-retry", "reserve").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    private String processVariable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }
}
