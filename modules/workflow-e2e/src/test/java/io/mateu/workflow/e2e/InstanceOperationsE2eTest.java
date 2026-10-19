package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessNotFoundException;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The manual operations on a running process — set its variables, complete a step by hand, run it
 * again from a step — end to end in embedded + memory mode, through the same door the REST API, the
 * console and the MCP tools use ({@link ProcessOperationsService}). Each leaves its audit line.
 */
class InstanceOperationsE2eTest extends AbstractE2eTest {

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.services.BatchOperationService batches;


    static final AuthorizationContext ALICE = new AuthorizationContext("alice", List.of(), List.of());

    @Autowired ProcessOperationsService operations;
    @Autowired IncidentService incidents;
    @Autowired LockService lockService;
    @Autowired(required = false) MeterRegistry meterRegistry;

    // ── Set variables ────────────────────────────────────────────────────────────────────────────

    @Test
    void settingAVariableReleasesAStepHeldByALinkGuard() {
        worker.on("prepare", TestWorker.succeed());
        worker.on("ship", TestWorker.succeed());
        createProcess("ops-guard", "ops-var-1", new io.mateu.workflow.dtos.Variable("approved", "false"));
        assertThat(process("ops-var-1").getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(step("ops-var-1", "ship").getStatus()).isEqualTo(StepExecutionStatus.CREATED);

        operations.setVariables(process("ops-var-1").getId(), List.of(new Variable("approved", "true"),
                new Variable("note", "ok")), List.of(), "Approved by phone", ALICE);

        assertThat(process("ops-var-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("ops-var-1", "approved")).isEqualTo("true");
        assertThat(variable("ops-var-1", "note")).isEqualTo("ok");
        var audit = auditOf("ops-var-1");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getWorkerId()).isEqualTo("operator:alice");
        assertThat(audit.getFirst().getMessage())
                .contains("[set-variables] by alice")
                .contains("set approved = 'true' (was 'false')")
                .contains("added note = 'ok'")
                .contains("reason: Approved by phone");
    }

    @Test
    void removingAVariableAndWritingTheSameValueAgainIsIdempotent() {
        worker.on("prepare", TestWorker.succeed());
        createProcess("ops-guard", "ops-var-2", new io.mateu.workflow.dtos.Variable("approved", "false"),
                new io.mateu.workflow.dtos.Variable("temp", "x"));
        var id = process("ops-var-2").getId();

        operations.setVariables(id, List.of(), List.of("temp"), "cleanup", ALICE);
        operations.setVariables(id, List.of(new Variable("approved", "false")), List.of("temp"), "again", ALICE);

        assertThat(process("ops-var-2").getVariables()).extracting(Variable::name).doesNotContain("temp");
        assertThat(auditOf("ops-var-2")).as("the second request changed nothing and records nothing").hasSize(1);
    }

    @Test
    void aFinishedProcessIsNotOperatedOn() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.succeed());
        createProcess("ops-linear", "ops-var-3");
        assertThat(process("ops-var-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var id = process("ops-var-3").getId();

        assertThatThrownBy(() -> operations.setVariables(id, List.of(new Variable("x", "1")), List.of(), "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("COMPLETED");
        assertThatThrownBy(() -> operations.retryFromStep(id, "b", "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class);
        assertThatThrownBy(() -> operations.completeStep(id, step("ops-var-3", "b").getId(), List.of(), "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class);
    }

    @Test
    void aReasonIsMandatory() {
        worker.on("prepare", TestWorker.succeed());
        createProcess("ops-guard", "ops-var-4", new io.mateu.workflow.dtos.Variable("approved", "false"));
        var id = process("ops-var-4").getId();

        assertThatThrownBy(() -> operations.setVariables(id, List.of(new Variable("x", "1")), List.of(), " ", ALICE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> operations.retryFromStep(id, "prepare", null, ALICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Complete a step by hand ──────────────────────────────────────────────────────────────────

    @Test
    void completingAFailedStepByHandLetsTheFlowCarryOnAndReopensTheProcess() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.failWith("Payment 4711 declined"));
        worker.on("c", TestWorker.succeed());
        createProcess("ops-linear", "ops-cmp-1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-cmp-1").getStatus()).isEqualTo(ProcessStatus.ERROR));

        operations.completeStep(process("ops-cmp-1").getId(), step("ops-cmp-1", "b").getId(),
                List.of(new Variable("paymentRef", "PHONE-1")), "Paid by phone", ALICE);

        assertThat(step("ops-cmp-1", "b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("c")).isEqualTo(1);
        assertThat(process("ops-cmp-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("ops-cmp-1", "paymentRef")).isEqualTo("PHONE-1");
        assertThat(auditOf("ops-cmp-1")).singleElement().satisfies(line -> {
            assertThat(line.getMessage()).contains("[complete-step] by alice").contains("completed step b (was ERROR)")
                    .contains("paymentRef = 'PHONE-1'").contains("running again").contains("Paid by phone");
            assertThat(line.getStepExecutionId()).isEqualTo(step("ops-cmp-1", "b").getId());
        });
    }

    @Test
    void aWorkerAnsweringAfterTheStepWasCompletedByHandIsDropped() {
        var requests = new CopyOnWriteArrayList<TaskExecutionRequested>();
        worker.on("a", (request, callback, invocation) -> requests.add(request));
        worker.on("b", TestWorker.deferForever());
        createProcess("ops-linear", "ops-cmp-2");
        var stepA = step("ops-cmp-2", "a");
        assertThat(stepA.getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);

        operations.completeStep(process("ops-cmp-2").getId(), stepA.getId(), List.of(), "Worker is stuck", ALICE);
        assertThat(step("ops-cmp-2", "a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("b")).isEqualTo(1);

        // The stuck worker comes back with an error for the attempt it was given.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(stepA.getId(), List.of(), "late failure",
                StepExecutionStatus.ERROR, false, requests.getFirst().attempt()));

        assertThat(step("ops-cmp-2", "a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("ops-cmp-2").getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @Test
    void anUnknownStepExecutionIsNotFound() {
        worker.on("a", TestWorker.deferForever());
        createProcess("ops-linear", "ops-cmp-3");

        assertThatThrownBy(() -> operations.completeStep(process("ops-cmp-3").getId(), "no-such-step", List.of(), "why", ALICE))
                .isInstanceOf(ProcessNotFoundException.class);
        assertThatThrownBy(() -> operations.completeStep("no-such-process", "x", List.of(), "why", ALICE))
                .isInstanceOf(ProcessNotFoundException.class);
        assertThatThrownBy(() -> operations.retryFromStep(process("ops-cmp-3").getId(), "no-such-step", "why", ALICE))
                .isInstanceOf(ProcessNotFoundException.class);
    }

    @Test
    void aStepThatHasNotStartedIsNotCompletedByHand() {
        worker.on("a", TestWorker.deferForever());
        createProcess("ops-linear", "ops-cmp-4");

        assertThatThrownBy(() -> operations.completeStep(process("ops-cmp-4").getId(), step("ops-cmp-4", "c").getId(),
                List.of(), "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("CREATED");
    }

    @Test
    void aSagaThatRolledBackIsNotTouched() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.fail());
        worker.on("refund", TestWorker.succeed());
        createProcess("compensation", "ops-cmp-5");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-cmp-5").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));

        assertThatThrownBy(() -> operations.completeStep(process("ops-cmp-5").getId(), step("ops-cmp-5", "ship").getId(),
                List.of(), "why", ALICE)).isInstanceOf(ProcessStateConflictException.class);
        assertThatThrownBy(() -> operations.retryFromStep(process("ops-cmp-5").getId(), "ship", "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class);
    }

    @Test
    void aSagaWhoseRollbackIsUnderWayIsNotTouched() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.fail());
        worker.on("refund", TestWorker.deferForever());
        createProcess("compensation", "ops-cmp-6");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(step("ops-cmp-6", "refund").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        assertThat(process("ops-cmp-6").getStatus()).isEqualTo(ProcessStatus.ERROR);

        assertThatThrownBy(() -> operations.completeStep(process("ops-cmp-6").getId(), step("ops-cmp-6", "ship").getId(),
                List.of(), "why", ALICE)).isInstanceOf(ProcessStateConflictException.class)
                .hasMessageContaining("rollback");
        assertThatThrownBy(() -> operations.retryFromStep(process("ops-cmp-6").getId(), "ship", "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("rollback");
    }

    // ── Retry from a step ────────────────────────────────────────────────────────────────────────

    @Test
    void retryingFromAnEarlierStepRunsItAndEverythingAfterItAgain() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.fail());
        createProcess("ops-linear", "ops-rfs-1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-rfs-1").getStatus()).isEqualTo(ProcessStatus.ERROR));
        assertThat(process("ops-rfs-1").getFinished()).isNotNull();

        worker.on("c", TestWorker.succeed());
        operations.retryFromStep(process("ops-rfs-1").getId(), "b", "Downstream fixed", ALICE);

        assertThat(process("ops-rfs-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("a")).as("upstream is left alone").isEqualTo(1);
        assertThat(worker.invocationsOf("b")).isEqualTo(2);
        assertThat(worker.invocationsOf("c")).isEqualTo(2);
        assertThat(auditOf("ops-rfs-1")).singleElement().satisfies(line -> assertThat(line.getMessage())
                .contains("[retry-from-step] by alice").contains("from step b")
                .contains("b (was COMPLETED)").contains("c (was ERROR)").contains("running again"));
    }

    @Test
    void retryingFromABranchOfAForkMakesTheJoinWaitForItAgain() {
        worker.on("branch-a", TestWorker.succeed());
        worker.on("branch-b", TestWorker.succeed());
        worker.on("after-join", TestWorker.deferForever());
        createProcess("fork-join", "ops-rfs-2");
        assertThat(step("ops-rfs-2", "join").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        worker.on("branch-a", TestWorker.deferForever());
        operations.retryFromStep(process("ops-rfs-2").getId(), "branch-a", "Re-run branch A", ALICE);

        assertThat(step("ops-rfs-2", "branch-a").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        assertThat(step("ops-rfs-2", "branch-b").getStatus()).as("the other branch is not downstream")
                .isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("ops-rfs-2", "join").getStatus()).as("the join waits for the re-run branch")
                .isEqualTo(StepExecutionStatus.CREATED);
        assertThat(step("ops-rfs-2", "after-join").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(worker.invocationsOf("branch-b")).isEqualTo(1);
    }

    @Test
    void aStaleReplyFromTheAbandonedAttemptIsDroppedAfterARetryFromAStep() {
        var requests = new CopyOnWriteArrayList<TaskExecutionRequested>();
        worker.on("a", TestWorker.succeed());
        worker.on("b", (request, callback, invocation) -> requests.add(request));
        createProcess("ops-linear", "ops-rfs-3");
        var b = step("ops-rfs-3", "b");

        operations.retryFromStep(process("ops-rfs-3").getId(), "b", "Worker restarted", ALICE);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).attempt()).isGreaterThan(requests.get(0).attempt());

        // The worker of the first dispatch answers now: it is not the current attempt.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(b.getId(), List.of(), "",
                StepExecutionStatus.COMPLETED, false, requests.get(0).attempt()));
        assertThat(step("ops-rfs-3", "b").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);

        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(b.getId(), List.of(), "",
                StepExecutionStatus.COMPLETED, false, requests.get(1).attempt()));
        assertThat(step("ops-rfs-3", "b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void retryingFromAStepBeforeAChoiceDecidesTheChoiceAgain() {
        worker.on("assess", TestWorker.succeed());
        worker.on("standard", TestWorker.fail());
        worker.on("express", TestWorker.succeed());
        createProcess("ops-choice", "ops-rfs-4", new io.mateu.workflow.dtos.Variable("tier", "silver"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-rfs-4").getStatus()).isEqualTo(ProcessStatus.ERROR));
        var id = process("ops-rfs-4").getId();

        // Retrying from the failed branch alone would take the same branch again; change the data, and
        // retry from before the choice: the latch is released and the choice decides again.
        operations.setVariables(id, List.of(new Variable("tier", "gold")), List.of(), "Upgraded", ALICE);
        operations.retryFromStep(id, "assess", "Re-route as gold", ALICE);

        assertThat(process("ops-rfs-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("express")).isEqualTo(1);
        assertThat(worker.invocationsOf("standard")).isEqualTo(1);
    }

    @Test
    void aStepThatHasNotRunYetIsNotRetriedFrom() {
        worker.on("a", TestWorker.deferForever());
        createProcess("ops-linear", "ops-rfs-5");

        assertThatThrownBy(() -> operations.retryFromStep(process("ops-rfs-5").getId(), "c", "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("has not run yet");
    }

    @Test
    void aFailureOutsideTheRetriedStepsRefusesTheRetry() {
        worker.on("branch-a", TestWorker.fail());
        worker.on("branch-b", TestWorker.fail());
        createProcess("fork-join", "ops-rfs-6");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("ops-rfs-6", "branch-a").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
            assertThat(step("ops-rfs-6", "branch-b").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        });

        assertThatThrownBy(() -> operations.retryFromStep(process("ops-rfs-6").getId(), "branch-a", "why", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("branch-b");
        // From the fork, both failures are downstream: allowed.
        worker.on("branch-a", TestWorker.succeed());
        worker.on("branch-b", TestWorker.succeed());
        worker.on("after-join", TestWorker.succeed());
        operations.retryFromStep(process("ops-rfs-6").getId(), "fork", "Both fixed", ALICE);
        assertThat(process("ops-rfs-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void retryingInsideACriticalSectionTakesTheLockAgain() {
        worker.on("work", TestWorker.fail());
        createProcess("ops-lock", "ops-rfs-7", new io.mateu.workflow.dtos.Variable("bookingId", "B-7"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-rfs-7").getStatus()).isEqualTo(ProcessStatus.ERROR));
        // A failed process gives its locks up.
        assertThat(lockService.tryAcquire("ops", "B-7", "probe-1")).isEqualTo(LockService.Outcome.ACQUIRED);
        lockService.release("ops", "B-7", "probe-1");

        worker.on("work", TestWorker.deferForever());
        operations.retryFromStep(process("ops-rfs-7").getId(), "work", "Retry inside the section", ALICE);

        assertThat(step("ops-rfs-7", "work").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        assertThat(step("ops-rfs-7", "lock").getStatus()).as("the LOCK step is not re-run").isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(lockService.tryAcquire("ops", "B-7", "probe-2")).as("the re-run holds the lock again")
                .isEqualTo(LockService.Outcome.BUSY);
        assertThat(auditOf("ops-rfs-7").getFirst().getMessage()).contains("re-took lock(s) ops/B-7");
    }

    @Test
    void whenTheLockIsTakenMeanwhileTheRetryMovesBackToTheLockStep() {
        worker.on("work", TestWorker.fail());
        createProcess("ops-lock", "ops-rfs-8", new io.mateu.workflow.dtos.Variable("bookingId", "B-8"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-rfs-8").getStatus()).isEqualTo(ProcessStatus.ERROR));
        assertThat(lockService.tryAcquire("ops", "B-8", "someone-else")).isEqualTo(LockService.Outcome.ACQUIRED);

        worker.on("work", TestWorker.succeed());
        operations.retryFromStep(process("ops-rfs-8").getId(), "work", "Retry", ALICE);

        assertThat(step("ops-rfs-8", "lock").getStatus()).as("the LOCK re-runs and queues")
                .isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);
        assertThat(step("ops-rfs-8", "work").getStatus()).isEqualTo(StepExecutionStatus.CREATED);

        // The other holder lets go: the queued LOCK is admitted and the section runs.
        lockService.release("ops", "B-8", "someone-else").ifPresent(grant ->
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(grant.stepExecutionId(), List.of(),
                        "admitted", StepExecutionStatus.COMPLETED)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("ops-rfs-8").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
    }

    @Test
    void aPausedProcessKeepsItsReopenedStepsUntilResumed() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.deferForever());
        createProcess("ops-linear", "ops-rfs-9");
        var id = process("ops-rfs-9").getId();
        processUpstreamEventUseCase.handle(new io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand(
                new io.mateu.workflow.dtos.events.integration.PauseProcessRequested(id)));
        assertThat(process("ops-rfs-9").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        operations.retryFromStep(id, "a", "Re-run while paused", ALICE);

        assertThat(process("ops-rfs-9").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(step("ops-rfs-9", "a").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(step("ops-rfs-9", "b").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(worker.invocationsOf("a")).isEqualTo(1);
    }

    // ── Incidents ────────────────────────────────────────────────────────────────────────────────

    @Test
    void failuresOfOneCauseGroupIntoOneIncidentThatIsRetriedAtOnce() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", (request, callback, invocation) ->
                callback.fail("Card " + request.taskExecutionId() + " declined: " + (400 + invocation)));
        worker.on("c", TestWorker.succeed());
        for (int i = 0; i < 3; i++) {
            createProcess("ops-linear", "ops-inc-" + i);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                assertThat(process("ops-inc-" + i).getStatus()).isEqualTo(ProcessStatus.ERROR);
            }
        });

        var group = incidents.incidents("ops-linear").groups().stream()
                .filter(g -> "b".equals(g.stepId())).findFirst().orElseThrow();
        assertThat(group.failedSteps()).isEqualTo(3);
        assertThat(group.processes()).isEqualTo(3);
        assertThat(group.errorFamily()).isEqualTo("Card <id> declined: #");
        assertThat(group.firstSeen()).isBeforeOrEqualTo(group.lastSeen());
        assertThat(incidents.members(group.key(), 10)).hasSize(3);

        worker.on("b", TestWorker.succeed());
        var outcome = incidents.retryGroup(group.key(), "Gateway fixed", ALICE);

        assertThat(outcome.requested()).isEqualTo(3);
        // A RETRY batch over the incident, walked in the background by the batch runner.
        assertThat(outcome.async()).isTrue();
        assertThat(outcome.batchOperationId()).isNotNull();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(batches.get(outcome.batchOperationId()).orElseThrow().status())
                    .isEqualTo(io.mateu.workflow.domain.batch.BatchOperationStatus.COMPLETED);
            for (int i = 0; i < 3; i++) {
                assertThat(process("ops-inc-" + i).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
            }
        });
        assertThat(batches.get(outcome.batchOperationId()).orElseThrow().succeeded()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(auditOf("ops-inc-" + i)).singleElement().satisfies(line ->
                    assertThat(line.getMessage()).contains("[retry-incident] by alice").contains("Gateway fixed")
                            .contains("(batch " + outcome.batchOperationId() + ")"));
        }
        assertThat(incidents.incidents("ops-linear").groups()).noneMatch(g -> g.key().equals(group.key()));
    }

    @Test
    void everyOperationIsCounted() {
        org.junit.jupiter.api.Assumptions.assumeTrue(meterRegistry != null, "no meter registry in this context");
        worker.on("prepare", TestWorker.succeed());
        createProcess("ops-guard", "ops-met-1", new io.mateu.workflow.dtos.Variable("approved", "false"));
        var before = counted("set-variables");

        operations.setVariables(process("ops-met-1").getId(), List.of(new Variable("approved", "maybe")), List.of(),
                "metric", ALICE);

        assertThat(counted("set-variables")).isEqualTo(before + 1);
    }

    private double counted(String operation) {
        return meterRegistry.find("eventconductor.processes.operations").tag("operation", operation).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private String variable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    /** The operator's audit lines of the process, newest first. */
    private List<LogMessage> auditOf(String businessKey) {
        return logMessageRepository.findByProcessId(process(businessKey).getId()).stream()
                .filter(OperatorAudit::isOperatorLine)
                .sorted(java.util.Comparator.comparing(LogMessage::getTimestamp).reversed())
                .toList();
    }
}
