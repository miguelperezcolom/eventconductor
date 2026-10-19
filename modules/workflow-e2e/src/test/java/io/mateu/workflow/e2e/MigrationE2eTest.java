package io.mateu.workflow.e2e;

import io.mateu.workflow.analysis.MigrationRules;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationParameters;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.MigrationScenario;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Moving running processes onto another version of their definition (2.31), in embedded + memory mode:
 * planned at the door, carried out by the owning node under the process lock, and the process running on
 * under the target version.
 */
class MigrationE2eTest extends AbstractE2eTest {

    static final AuthorizationContext BOB = new AuthorizationContext("bob", List.of(), List.of());
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired ProcessOperationsService operations;
    @Autowired BatchOperationService batches;
    @Autowired WorkflowDefinitionRepository definitions;

    MigrationScenario scenario;
    /** The callbacks of tasks left at the worker, by step id: the test answers them when it chooses. */
    final Map<String, TestWorker.WorkerCallback> held = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        scenario = new MigrationScenario(definitions);
        held.clear();
    }

    @AfterEach
    void tearDown() {
        scenario.restore();
    }

    private TestWorker.Behavior hold(String key) {
        return (request, callback, n) -> held.put(key, callback);
    }

    private void awaitStatus(String businessKey, ProcessStatus status) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(process(businessKey).getStatus()).isEqualTo(status));
    }

    private void awaitStep(String businessKey, String stepId, StepExecutionStatus... statuses) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(current(businessKey, stepId).getStatus()).isIn((Object[]) statuses));
    }

    private StepExecution current(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(se -> stepId.equals(se.getStepId()) && !se.isSuperseded() && !se.isIteration())
                .findFirst().orElseThrow(() -> new AssertionError("no current execution of " + stepId));
    }

    private List<String> audit(String businessKey) {
        return logMessageRepository.findByProcessId(process(businessKey).getId()).stream()
                .filter(OperatorAudit::isOperatorLine).map(LogMessage::getMessage).toList();
    }

    private String id(String businessKey) {
        return process(businessKey).getId();
    }

    @Test
    void anUnchangedStepKeepsItsTaskAndNewStepsRun() {
        worker.on("b", hold("b"));
        createProcess("mig-linear", "mig-1");
        awaitStep("mig-1", "b", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        var bBefore = current("mig-1", "b");

        scenario.deploy("mig-linear.v2-added.json");
        operations.migrate(id("mig-1"), null, Map.of(), Map.of(), "v2 adds the delivery step", BOB);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(process("mig-1").getWorkflowDefinitionVersion()).isEqualTo(2));
        var bAfter = current("mig-1", "b");
        assertThat(bAfter.getId()).isEqualTo(bBefore.getId());
        assertThat(bAfter.getDispatchCount()).isEqualTo(bBefore.getDispatchCount());
        // x is new, upstream of where the process is: passed, never run. c has no counterpart: dropped.
        assertThat(current("mig-1", "x").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(steps("mig-1")).anySatisfy(se -> {
            assertThat(se.getStepId()).isEqualTo("c");
            assertThat(se.isSuperseded()).isTrue();
        });

        // The worker's answer to the kept task lands; the flow goes on in v2.
        held.get("b").complete();
        awaitStatus("mig-1", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("b")).isEqualTo(1);
        assertThat(worker.invocationsOf("d")).isEqualTo(1);
        assertThat(worker.invocationsOf("x")).isZero();
        assertThat(worker.invocationsOf("c")).isZero();
        assertThat(audit("mig-1")).anySatisfy(line -> assertThat(line)
                .contains("[migrate] by bob").contains("migrated from v1 to v2").contains("add passed [x]")
                .contains("drop [c]").contains("keep task [b]").contains("v2 adds the delivery step"));
    }

    @Test
    void aRenamedStepWhoseContractChangedIsDispatchedAgainAndTheOldAnswerIsDropped() {
        worker.on("b", hold("b"));
        worker.on("b2", TestWorker.succeed(TestWorker.var("fixed", "yes")));
        createProcess("mig-linear", "mig-2");
        awaitStep("mig-2", "b", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);

        scenario.deploy("mig-linear.v2-renamed.json");
        operations.migrate(id("mig-2"), null, Map.of("b", "b2"), Map.of(), "b was broken", BOB);

        awaitStatus("mig-2", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("b2")).isEqualTo(1);
        assertThat(current("mig-2", "b2").topic()).isEqualTo("work-v2");
        assertThat(process("mig-2").getVariables()).contains(new io.mateu.workflow.domain.aggregates.Variable("fixed", "yes"));
        // The abandoned attempt answers late: dropped, nothing moves.
        held.get("b").fail("too late");
        assertThat(process("mig-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(audit("mig-2")).anySatisfy(line -> assertThat(line).contains("mapping [b→b2]").contains("redispatch [b→b2]"));
    }

    @Test
    void aWaitingMessageAndATimerAreReArmed() {
        createProcess("mig-wait", "mig-3", new Variable("orderId", "O-1"));
        awaitStep("mig-3", "wait", StepExecutionStatus.PENDING);
        awaitStep("mig-3", "cool", StepExecutionStatus.PENDING);

        scenario.deploy("mig-wait.v2.json");
        // v2 correlates by invoiceId, which the process does not have: refused, unless the migration gives it.
        assertThatThrownBy(() -> operations.migrate(id("mig-3"), null, Map.of(), Map.of(), "rename", BOB))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("invoiceId");
        operations.migrate(id("mig-3"), null, Map.of(), Map.of("invoiceId", "INV-9"), "rename the message", BOB);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var wait = current("mig-3", "wait");
            assertThat(wait.getAwaitingMessageName()).isEqualTo("payment-settled");
            assertThat(wait.getAwaitingCorrelationKey()).isEqualTo("INV-9");
        });
        // The timer kept its start: half a second under v2 has long passed, so it fires.
        awaitStep("mig-3", "cool", StepExecutionStatus.COMPLETED);
        // The old message name no longer wakes it; the new one does.
        send("payment-received", "O-1");
        assertThat(current("mig-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        send("payment-settled", "INV-9");
        awaitStatus("mig-3", ProcessStatus.COMPLETED);
    }

    @Test
    void aForEachMovesWithItsIterationsAndTheOneAtTheWorkerIsDispatchedAgain() {
        worker.on("line", (request, callback, n) -> {
            if (n == 1) {
                held.put("line", callback);
            } else {
                callback.complete(List.of(TestWorker.var("ok", "v" + n)));
            }
        });
        createProcess("mig-foreach", "mig-4",
                new Variable("order", "{\"lines\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}"));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(held).containsKey("line"));

        scenario.deploy("mig-foreach.v2.yaml");
        var plan = operations.dryRunMigration(id("mig-4"), null, Map.of(), Map.of(), BOB).plan();
        assertThat(plan.executable()).isTrue();
        assertThat(plan.ofSource("line").action()).isEqualTo(MigrationRules.Action.REARM);
        operations.migrate(id("mig-4"), null, Map.of(), Map.of(), "lines on the new topic", BOB);

        awaitStatus("mig-4", ProcessStatus.COMPLETED);
        assertThat(steps("mig-4")).filteredOn(StepExecution::isIteration)
                .allSatisfy(iteration -> assertThat(iteration.topic()).isEqualTo("work-v2"));
        assertThat(worker.invocationsOf("summary")).isEqualTo(1);
        held.get("line").complete();
        assertThat(process("mig-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aLoopsEarlierPassesFollowItsStep() {
        worker.on("poll", (request, callback, n) -> {
            if (n == 1) {
                callback.complete(List.of(TestWorker.var("paymentStatus", "PENDING")));
            } else {
                held.put("poll", callback);
            }
        });
        createProcess("mig-repeat", "mig-5");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(held).containsKey("poll"));

        scenario.deploy("mig-repeat.v2.yaml");
        operations.migrate(id("mig-5"), null, Map.of("poll", "check-payment"), Map.of(), "renamed", BOB);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(process("mig-5").getWorkflowDefinitionVersion()).isEqualTo(2));

        // The pass in flight kept its task (same contract): its answer settles the loop under the new id.
        held.get("poll").complete(List.of(TestWorker.var("paymentStatus", "SETTLED")));
        awaitStatus("mig-5", ProcessStatus.COMPLETED);
        // The first pass, history, is now under the new id too; nothing is left under the old one.
        assertThat(steps("mig-5")).noneMatch(se -> "poll".equals(se.getStepId()));
        assertThat(steps("mig-5").stream().filter(se -> "check-payment".equals(se.getStepId()))).hasSizeGreaterThanOrEqualTo(2);
        assertThat(worker.invocationsOf("ship")).isEqualTo(1);
    }

    @Test
    void aProcessInsideACriticalSectionKeepsItsLockOrIsRefused() {
        worker.on("work", hold("work"));
        createProcess("mig-lock", "mig-6", new Variable("bookingId", "B-1"));
        awaitStep("mig-6", "work", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);

        scenario.deploy("mig-lock.v2-other-lock.json");
        assertThatThrownBy(() -> operations.migrate(id("mig-6"), null, Map.of(), Map.of(), "other lock", BOB))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("holds the lock taken by 'lock'");

        scenario.deploy("mig-lock.v2-same-lock.json");
        operations.migrate(id("mig-6"), null, Map.of(), Map.of(), "audit inside the section", BOB);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(process("mig-6").getWorkflowDefinitionVersion()).isEqualTo(2));
        held.get("work").complete();
        awaitStatus("mig-6", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("audit")).isEqualTo(1);
    }

    @Test
    void aSagaRollingBackIsRefused() {
        worker.on("ship", TestWorker.failWith("[non-retryable] no stock"));
        worker.on("refund", TestWorker.deferForever());
        createProcess("mig-saga", "mig-7");
        awaitStep("mig-7", "refund", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);

        assertThatThrownBy(() -> operations.migrate(id("mig-7"), null, Map.of(), Map.of(), "too late", BOB))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("rollback is under way");
    }

    @Test
    void anActiveStepWithoutCounterpartIsRefusedAndTheDryRunChangesNothing() {
        worker.on("b", TestWorker.deferForever());
        createProcess("mig-linear", "mig-8");
        awaitStep("mig-8", "b", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        scenario.deploy("mig-linear.v2-timer.json");

        var preview = operations.dryRunMigration(id("mig-8"), null, Map.of(), Map.of(), BOB);
        assertThat(preview.fromVersion()).isEqualTo(1);
        assertThat(preview.toVersion()).isEqualTo(2);
        assertThat(preview.plan().executable()).isFalse();
        assertThat(preview.plan().errors()).anySatisfy(p -> assertThat(p.message()).contains("'b' is ACTIVE"));
        assertThat(process("mig-8").getWorkflowDefinitionVersion()).isEqualTo(1);

        assertThatThrownBy(() -> operations.migrate(id("mig-8"), null, Map.of(), Map.of(), "no", BOB))
                .isInstanceOf(ProcessStateConflictException.class);
        assertThatThrownBy(() -> operations.migrate(id("mig-8"), 7, Map.of(), Map.of(), "no", BOB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keeps no version history");
        assertThatThrownBy(() -> operations.migrate(id("mig-8"), null, Map.of(), Map.of(), " ", BOB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMigrateBatchMovesEveryProcessOfTheVersionAndSkipsTheOnesItCannot() {
        worker.on("b", TestWorker.deferForever());
        createProcess("mig-linear", "mig-b1");
        createProcess("mig-linear", "mig-b2");
        awaitStep("mig-b1", "b", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        awaitStep("mig-b2", "b", StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        scenario.deploy("mig-linear.v2-renamed.json");
        worker.on("b2", TestWorker.deferForever());

        var batch = batches.create(BatchOperationType.MIGRATE,
                new BatchOperationFilter("mig-linear", null, Set.of(ProcessStatus.RUNNING), null, null, "mig-b", null,
                        false, null),
                BatchOperationParameters.migrate(null, 1, Map.of("b", "b2"), Map.of(), "fleet fix"), BOB);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(batches.get(batch.id()).orElseThrow().status()).isEqualTo(BatchOperationStatus.COMPLETED));

        var done = batches.get(batch.id()).orElseThrow();
        assertThat(done.succeeded()).isEqualTo(2);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(process("mig-b1").getWorkflowDefinitionVersion()).isEqualTo(2);
            assertThat(process("mig-b2").getWorkflowDefinitionVersion()).isEqualTo(2);
            assertThat(current("mig-b1", "b2").getStatus()).isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        });
        assertThat(audit("mig-b1")).anySatisfy(line -> assertThat(line).contains("fleet fix (batch " + batch.id() + ")"));

        // Run again from v1: nothing is on v1 any more, so both are skipped as not applicable.
        var again = batches.create(BatchOperationType.MIGRATE,
                new BatchOperationFilter("mig-linear", null, Set.of(ProcessStatus.RUNNING), null, null, "mig-b", null,
                        false, null),
                BatchOperationParameters.migrate(null, 1, Map.of("b", "b2"), Map.of(), "again"), BOB);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(batches.get(again.id()).orElseThrow().status()).isEqualTo(BatchOperationStatus.COMPLETED));
        assertThat(batches.get(again.id()).orElseThrow().skipped()).isEqualTo(2);

        assertThatThrownBy(() -> batches.create(BatchOperationType.MIGRATE,
                new BatchOperationFilter(null, "a-tag", null, null, null, null, null, false, null),
                BatchOperationParameters.migrate(null, null, Map.of(), Map.of(), "r"), BOB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must name the definition");
    }

    private void send(String messageName, String key) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(new MessageReceived(messageName, key, List.of())));
    }
}
