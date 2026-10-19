package io.mateu.workflow.application.updates;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import io.mateu.workflow.infra.out.memory.InMemoryProcessUpdateRepository;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The update door and the owner's use case, wired by hand over an in-memory update store. */
class ProcessUpdateServiceTest {

    static final String DEFINITION = """
            {"id": "orders", "name": "Orders", "version": 3,
             "steps": [
               {"id": "start", "type": "START", "name": "Start"},
               {"id": "wait", "type": "WAIT_FOR_MESSAGE", "name": "Wait", "messageName": "go",
                "correlationExpression": "businessKey", "preconditionStepId": "start"},
               {"id": "end", "type": "END", "name": "End", "preconditionStepId": "wait"}
             ],
             "requiredRoles": ["clerk"],
             "updates": [
               {"name": "rename", "accepts": {"type": "object", "required": ["name"]},
                "validate": [{"expression": "payload.name != 'forbidden'", "message": "Not that name."}],
                "set": {"customer": "payload.name"}, "reply": "{'customer': customer}", "sensitive": ["pin"]},
               {"name": "release", "completes": "wait", "set": {"by": "payload.by"}, "reply": "by"},
               {"name": "onlyWaiting", "acceptedWhile": ["wait"], "reply": "'ok'"},
               {"name": "touch"},
               {"name": "broken", "set": {"x": "1 / 0"}}
             ]}""";

    final ProcessRepository processes = mock(ProcessRepository.class);
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final LogMessageRepository logs = mock(LogMessageRepository.class);
    final ProcessLockService lock = mock(ProcessLockService.class);
    final MessageSubscriptionService subscriptions = mock(MessageSubscriptionService.class);
    final StepOverProcessUseCase stepOver = mock(StepOverProcessUseCase.class);
    final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final CreateProcessUseCase create = mock(CreateProcessUseCase.class);
    final CommandDispatcher dispatcher = mock(CommandDispatcher.class);
    final InMemoryProcessUpdateRepository updates = new InMemoryProcessUpdateRepository();
    final ProcessUpdateWaiters waiters = new ProcessUpdateWaiters(updates);
    final List<String> lines = new ArrayList<>();

    WorkflowDefinition definition;
    ApplyProcessUpdateUseCase apply;
    ProcessUpdateService service;
    StepExecution waiting;

    @BeforeEach
    void setUp() throws Exception {
        definition = new ObjectMapper().findAndRegisterModules().readValue(DEFINITION, WorkflowDefinition.class);
        when(definitions.findById("orders")).thenReturn(Optional.of(definition));
        when(lock.runExclusively(anyString(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
        when(logs.save(any())).thenAnswer(invocation -> {
            lines.add(((LogMessage) invocation.getArgument(0)).getMessage());
            return "id";
        });
        waiters.pollIntervalMs = 20;
        waiters.start();
        apply = new ApplyProcessUpdateUseCase(updates, processes, steps, logs, lock, subscriptions, stepOver, metrics, waiters);
        service = new ProcessUpdateService(processes, updates, definitions, create, dispatcher, apply, waiters, logs,
                metrics, new StaticListableBeanFactory().getBeanProvider(UpdateWithStartPolicy.class));
        waiting = StepExecution.builder().id("se-wait").processId("p").stepId("wait")
                .workflowDefinitionId("orders").status(StepExecutionStatus.PENDING)
                .startedAt(EngineClock.now().minusSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        waiters.stop();
    }

    Process process(ProcessStatus status, Variable... variables) {
        var process = Process.create("p", definition, "order-1", new ArrayList<>(List.of(variables)))
                .withStatus(status);
        when(processes.findById("p")).thenReturn(Optional.of(process));
        when(processes.findByBusinessKey("order-1")).thenReturn(Optional.of(process));
        return process;
    }

    void stepsAre(StepExecution... executions) {
        when(steps.findByProcessId("p")).thenReturn(List.of(executions));
    }

    ProcessUpdate inline(String name, String payload, String key) {
        var submitted = service.submitInline(new ProcessUpdateService.UpdateRequest("p", null, name, payload, key, null, null));
        return updates.findById(submitted.update().id()).orElseThrow();
    }

    // ── Applying ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void anAcceptedUpdateWritesItsVariablesAndRecordsItsResult() {
        var process = process(ProcessStatus.RUNNING);
        stepsAre();

        var update = inline("rename", "{\"name\": \"Ana\", \"pin\": \"1234\"}", null);

        assertThat(update.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(update.result()).isEqualTo("{\"customer\":\"Ana\"}");
        assertThat(update.payload()).isEqualTo("{\"name\":\"Ana\",\"pin\":\"••••\"}");
        assertThat(update.idempotencyKey()).isEqualTo(update.id());
        assertThat(process.getVariables()).containsExactly(new Variable("customer", "Ana"));
        verify(processes).save(process);
        verify(subscriptions).rearm(process);
        verify(stepOver).handle(any());
        verify(metrics).processUpdateFinished("orders", "rename", "COMPLETED");
        assertThat(lines).singleElement().asString().isEqualTo("Update 'rename' applied: set customer");
    }

    @Test
    void anUpdateThatCompletesAStepResumesIt() {
        process(ProcessStatus.RUNNING);
        stepsAre(waiting);

        var update = inline("release", "{\"by\": \"luis\"}", null);

        assertThat(update.result()).isEqualTo("\"luis\"");
        assertThat(waiting.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        verify(steps).save(waiting);
        verify(stepOver, never()).handle(any());
        assertThat(lines).singleElement().asString().contains("resumed step 'wait'");
    }

    @Test
    void anUpdateWithNothingToWriteOnlyAnswers() {
        process(ProcessStatus.PENDING);
        stepsAre();

        var update = inline("touch", null, null);

        assertThat(update.result()).isEqualTo("{}");
        verify(processes, never()).save(any());
        verify(stepOver, never()).handle(any());
    }

    @Test
    void anUpdateWhoseStepIsNotWaitingIsNotAccepted() {
        process(ProcessStatus.RUNNING);
        stepsAre(waiting.withStatus(StepExecutionStatus.CREATED));

        assertThat(inline("onlyWaiting", null, null).status()).isEqualTo(ProcessUpdate.Status.NOT_ACCEPTED);
        var release = inline("release", "{}", null);
        assertThat(release.status()).isEqualTo(ProcessUpdate.Status.NOT_ACCEPTED);
        assertThat(release.error()).contains("'wait', which is not waiting");
        verify(processes, never()).save(any());
    }

    @Test
    void aStepInProgressOpensTheWindow() {
        process(ProcessStatus.RUNNING);
        stepsAre(waiting.withStatus(StepExecutionStatus.RUNNING));

        assertThat(inline("onlyWaiting", null, null).result()).isEqualTo("\"ok\"");
    }

    @Test
    void aGuardRefusesAndAnExpressionFails() {
        var process = process(ProcessStatus.RUNNING, new Variable("customer", "Old"));
        stepsAre();

        var refused = inline("rename", "{\"name\": \"forbidden\"}", null);
        var failed = inline("broken", null, null);

        assertThat(refused.status()).isEqualTo(ProcessUpdate.Status.REFUSED);
        assertThat(refused.error()).isEqualTo("Not that name.");
        assertThat(failed.status()).isEqualTo(ProcessUpdate.Status.FAILED);
        assertThat(process.getVariables()).containsExactly(new Variable("customer", "Old"));
        verify(processes, never()).save(any());
        assertThat(lines).anyMatch(line -> line.startsWith("Update 'rename' refused"))
                .anyMatch(line -> line.startsWith("Update 'broken' failed"));
    }

    @Test
    void theOwnerDecidesForGoodUnderTheLock() {
        var process = process(ProcessStatus.RUNNING);
        stepsAre();
        var id = "u-1";
        updates.insert(new ProcessUpdate(id, "p", "orders", "rename", id, null, null, ProcessUpdate.Status.REQUESTED,
                null, null, "ana", false, EngineClock.now(), null, null));
        // Between the door and the owner the process failed.
        when(processes.findById("p")).thenReturn(Optional.of(process.withStatus(ProcessStatus.ERROR)));

        apply.handle(new ProcessUpdateRequested(id, "p", "rename", "{\"name\": \"x\"}", "ana"));

        var update = updates.findById(id).orElseThrow();
        assertThat(update.status()).isEqualTo(ProcessUpdate.Status.NOT_ACCEPTED);
        assertThat(update.error()).contains("ERROR");
        assertThat(lines).singleElement().asString().contains("(by ana)");

        // A redelivery changes nothing.
        apply.handle(new ProcessUpdateRequested(id, "p", "rename", "{\"name\": \"x\"}", "ana"));
        assertThat(lines).hasSize(1);
    }

    @Test
    void aProcessThatIsGoneOrRunsADefinitionWithoutTheUpdateIsNotAccepted() {
        var process = process(ProcessStatus.RUNNING);
        stepsAre();
        updates.insert(new ProcessUpdate("u-2", "p", "orders", "nope", "u-2", null, null, ProcessUpdate.Status.REQUESTED,
                null, null, null, false, EngineClock.now(), null, null));
        updates.insert(new ProcessUpdate("u-3", "p", "orders", "rename", "u-3", null, null, ProcessUpdate.Status.REQUESTED,
                null, null, null, false, EngineClock.now(), null, null));
        updates.insert(new ProcessUpdate("u-4", "p", "orders", "rename", "u-4", null, null, ProcessUpdate.Status.REQUESTED,
                null, null, null, false, EngineClock.now(), null, null));

        apply.handle(new ProcessUpdateRequested("u-2", "p", "nope", null, null));
        apply.handle(new ProcessUpdateRequested("u-4", "p", "rename", "{not json", null));
        when(processes.findById("p")).thenReturn(Optional.empty());
        apply.handle(new ProcessUpdateRequested("u-3", "p", "rename", "{}", null));

        assertThat(updates.findById("u-2").orElseThrow().error()).contains("declares no update 'nope'");
        assertThat(updates.findById("u-4").orElseThrow().status()).isEqualTo(ProcessUpdate.Status.FAILED);
        assertThat(updates.findById("u-3").orElseThrow().error()).isEqualTo("The process no longer exists.");
        assertThat(process.getVariables()).isEmpty();
    }

    @Test
    void aCommandProducedStraightToTheTopicIsRecordedByTheOwner() {
        process(ProcessStatus.RUNNING);
        stepsAre();

        apply.handle(new ProcessUpdateRequested("k-1", "p", "rename", "{\"name\": \"Kafka\", \"pin\": \"1\"}", "svc"));
        apply.handle(new ProcessUpdateRequested("k-2", "p", "rename", "{\"nombre\": \"x\"}", "svc"));
        apply.handle(new ProcessUpdateRequested("k-3", "gone", "rename", "{}", "svc"));

        var applied = updates.findById("k-1").orElseThrow();
        assertThat(applied.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(applied.idempotencyKey()).isEqualTo("k-1");
        assertThat(applied.payload()).contains("••••");
        assertThat(updates.findById("k-2").orElseThrow().status()).isEqualTo(ProcessUpdate.Status.FAILED);
        assertThat(updates.findById("k-2").orElseThrow().error()).contains("payload is not valid");
        assertThat(updates.findById("k-3")).isEmpty();
    }

    // ── The door ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void theDoorRefusesWhatItCanSeeWithoutTheLock() {
        process(ProcessStatus.RUNNING);
        stepsAre();

        assertReason(() -> inline("nope", "{}", null), ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE);
        assertReason(() -> inline("rename", "{\"x\": 1}", null), ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD);
        assertReason(() -> inline("rename", "{oops", null), ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD);
        assertReason(() -> inline("rename", "{}", "k".repeat(10_000)), ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD);
        assertReason(() -> inline("", "{}", null), ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE);
        assertReason(() -> service.submit(new ProcessUpdateService.UpdateRequest("x", null, "rename", "{}", null, null, null)),
                ProcessUpdateRejectedException.Reason.NO_SUCH_PROCESS);
        assertReason(() -> service.submit(new ProcessUpdateService.UpdateRequest(null, "nobody", "rename", "{}", null, null, null)),
                ProcessUpdateRejectedException.Reason.NO_SUCH_PROCESS);
        assertReason(() -> service.submit(new ProcessUpdateService.UpdateRequest(null, null, "rename", "{}", null, null, null)),
                ProcessUpdateRejectedException.Reason.NO_SUCH_PROCESS);
        process(ProcessStatus.COMPLETED);
        assertReason(() -> inline("rename", "{\"name\": \"x\"}", null), ProcessUpdateRejectedException.Reason.NOT_ACCEPTING);
        assertThat(updates.findByProcessId("p")).isEmpty();
    }

    @Test
    void aRetryWithTheKeyGetsTheSameUpdateAndAnotherRequestIsRefused() {
        process(ProcessStatus.RUNNING);
        stepsAre();

        var first = inline("rename", "{\"name\": \"Ana\"}", "key");
        var again = inline("rename", "{ \"name\" : \"Ana\" }", "key");

        assertThat(again.id()).isEqualTo(first.id());
        verify(processes).save(any());
        assertReason(() -> inline("rename", "{\"name\": \"Eva\"}", "key"), ProcessUpdateRejectedException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertReason(() -> inline("touch", "{\"name\": \"Ana\"}", "key"), ProcessUpdateRejectedException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aRetryOfAnUpdateStillRequestedSendsItAgain() {
        process(ProcessStatus.RUNNING);
        stepsAre();
        service.mode = "kafka";
        var request = new ProcessUpdateService.UpdateRequest("p", null, "rename", "{\"name\": \"Ana\"}", "key", null, null);

        var first = service.submit(request);
        var again = service.submit(request);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.update().status()).isEqualTo(ProcessUpdate.Status.REQUESTED);
        verify(dispatcher, org.mockito.Mockito.times(2)).dispatch(any(ProcessUpdateRequested.class));
    }

    @Test
    void inKafkaModeTheCommandGoesToTheOwnerAndABrokerThatSaysNoIsUnavailable() {
        process(ProcessStatus.RUNNING);
        stepsAre();
        service.mode = "kafka";

        var submitted = service.submit(new ProcessUpdateService.UpdateRequest("p", null, "rename", "{\"name\": \"A\"}",
                null, Duration.ofSeconds(3), null));

        assertThat(submitted.update().status()).isEqualTo(ProcessUpdate.Status.REQUESTED);
        assertThat(submitted.timeToWait()).isEqualTo(Duration.ofSeconds(3));
        verify(dispatcher).dispatch(any(ProcessUpdateRequested.class));

        doThrow(new IllegalStateException("broker down")).when(dispatcher).dispatch(any());
        assertReason(() -> service.submit(new ProcessUpdateService.UpdateRequest("p", null, "rename", "{\"name\": \"B\"}",
                null, null, null)), ProcessUpdateRejectedException.Reason.UNAVAILABLE);
    }

    @Test
    void embeddedAppliesOffTheRequestThreadAndTheCallerWaits() throws Exception {
        process(ProcessStatus.RUNNING);
        stepsAre();

        var submitted = service.submit(new ProcessUpdateService.UpdateRequest("p", null, "rename", "{\"name\": \"Ana\"}",
                null, null, null));
        var outcome = service.await(submitted.update(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(service.await(outcome, Duration.ZERO).get()).isSameAs(outcome);
    }

    @Test
    void aProcessThatStaysBusyLeavesTheUpdateRequested() throws Exception {
        process(ProcessStatus.RUNNING);
        stepsAre();
        org.mockito.Mockito.doReturn(false).when(lock).runExclusively(anyString(), any());

        var submitted = service.submit(new ProcessUpdateService.UpdateRequest("p", null, "rename", "{\"name\": \"Ana\"}",
                null, null, null));
        var outcome = service.await(submitted.update(), Duration.ofMillis(300)).get(10, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(ProcessUpdate.Status.REQUESTED);
        assertThatThrownBy(() -> apply.handle(new ProcessUpdateRequested(outcome.id(), "p", "rename", "{}", null)))
                .isInstanceOf(ConcurrentProcessAccessException.class);
    }

    @Test
    void waitsAreCapped() {
        assertThat(service.waitFor(null)).isEqualTo(Duration.ofMillis(5000));
        assertThat(service.waitFor(Duration.ofHours(1))).isEqualTo(Duration.ofMillis(30000));
        assertThat(service.waitFor(Duration.ofSeconds(-1))).isEqualTo(Duration.ZERO);
    }

    @Test
    void readsListTheProcessesUpdates() {
        process(ProcessStatus.RUNNING);
        stepsAre();
        var update = inline("rename", "{\"name\": \"Ana\"}", null);

        assertThat(service.find(update.id(), null)).contains(update);
        assertThat(service.find("nope", null)).isEmpty();
        assertThat(service.list("p", null)).containsExactly(update);
    }

    @Test
    void flowAuthorizationGuardsUpdatesAndReads() {
        process(ProcessStatus.RUNNING);
        stepsAre();
        service.flowAuthorizationEnabled = true;
        var clerk = new AuthorizationContext("ana", List.of(), List.of("clerk"));
        var stranger = new AuthorizationContext("eve", List.of(), List.of());

        var update = service.submitInline(new ProcessUpdateService.UpdateRequest("p", null, "touch", null, null, null, clerk));
        assertThat(update.update().requestedBy()).isEqualTo("ana");
        assertThatThrownBy(() -> service.submitInline(new ProcessUpdateService.UpdateRequest("p", null, "touch", null,
                null, null, stranger))).isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.list("p", stranger)).isInstanceOf(FlowAuthorizationDeniedException.class);
    }

    // ── Update-with-start ────────────────────────────────────────────────────────────────────────

    ProcessUpdateService.StartRequest startRequest(String businessKey, String name, String payload, String key) {
        return new ProcessUpdateService.StartRequest("orders", businessKey, Map.of("customer", "Initial"), name, payload,
                key, null, null);
    }

    @Test
    void withNoProcessForTheKeyTheUpdateStartsOne() {
        when(processes.findByBusinessKey("new-1")).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            CreateProcessCommand command = invocation.getArgument(0);
            var created = Process.create(command.processId(), definition, command.businessKey(),
                    new ArrayList<>(command.variables()));
            when(processes.findById(command.processId())).thenReturn(Optional.of(created));
            assertThat(command.variables()).containsExactly(new Variable("customer", "Ana"));
            return null;
        }).when(create).handle(any());

        var started = service.updateWithStart(startRequest("new-1", "rename", "{\"name\": \"Ana\"}", null));

        assertThat(started.started()).isTrue();
        assertThat(started.update().status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(started.update().withStart()).isTrue();
        assertThat(started.update().result()).isEqualTo("{\"customer\":\"Ana\"}");
        assertThat(updates.findById(started.update().id())).isPresent();
        assertThat(lines).singleElement().asString().isEqualTo("Update 'rename' applied with the start");
        verify(metrics).processUpdateFinished("orders", "rename", "COMPLETED");
    }

    @Test
    void withAProcessForTheKeyTheUpdateGoesToIt() {
        process(ProcessStatus.RUNNING);
        stepsAre();

        var outcome = service.updateWithStartInline(startRequest("order-1", "rename", "{\"name\": \"Eva\"}", null));

        assertThat(outcome.started()).isFalse();
        assertThat(updates.findById(outcome.update().id()).orElseThrow().status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        verify(create, never()).handle(any());
    }

    @Test
    void aStartThatLosesTheKeyRaceGoesToTheWinner() {
        var winner = Process.create("p", definition, "race", new ArrayList<>()).withStatus(ProcessStatus.RUNNING);
        when(processes.findByBusinessKey("race")).thenReturn(Optional.empty(), Optional.of(winner));
        when(processes.findById("p")).thenReturn(Optional.of(winner));
        stepsAre();

        var outcome = service.updateWithStartInline(startRequest("race", "rename", "{\"name\": \"Eva\"}", null));

        assertThat(outcome.started()).isFalse();
        assertThat(outcome.update().processId()).isEqualTo("p");
    }

    @Test
    void anUpdateWithStartThatCannotBeAppliedStartsNothing() {
        when(processes.findByBusinessKey(anyString())).thenReturn(Optional.empty());
        var otherDefinition = Process.create("q", definition.withUpdates(null), "theirs", new ArrayList<>());
        when(processes.findByBusinessKey("theirs")).thenReturn(Optional.of(otherDefinition.withWorkflowDefinitionId("billing")));

        assertReason(() -> service.updateWithStart(startRequest("k", "rename", "{\"name\": \"forbidden\"}", null)),
                ProcessUpdateRejectedException.Reason.REFUSED);
        assertReason(() -> service.updateWithStart(startRequest("k", "broken", null, null)),
                ProcessUpdateRejectedException.Reason.FAILED);
        assertReason(() -> service.updateWithStart(startRequest("k", "rename", "{}", null)),
                ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD);
        assertReason(() -> service.updateWithStart(startRequest(" ", "rename", "{}", null)),
                ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD);
        assertReason(() -> service.updateWithStart(startRequest("k", "nope", "{}", null)),
                ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE);
        assertReason(() -> service.updateWithStart(new ProcessUpdateService.StartRequest("missing", "k", null, "rename",
                "{}", null, null, null)), ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE);
        assertReason(() -> service.updateWithStart(startRequest("theirs", "rename", "{\"name\": \"x\"}", null)),
                ProcessUpdateRejectedException.Reason.BUSINESS_KEY_TAKEN);
        when(definitions.findById("orders")).thenReturn(Optional.of(definition.withRuntimeStatus(
                io.mateu.workflow.domain.aggregates.WorkflowStatus.DISABLED)));
        assertReason(() -> service.updateWithStart(startRequest("k", "rename", "{\"name\": \"x\"}", null)),
                ProcessUpdateRejectedException.Reason.NOT_ACCEPTING);
        // The creation declined twice (the key keeps being taken under us): refused, not looped.
        when(definitions.findById("orders")).thenReturn(Optional.of(definition));
        assertReason(() -> service.updateWithStart(startRequest("k", "rename", "{\"name\": \"x\"}", null)),
                ProcessUpdateRejectedException.Reason.NOT_ACCEPTING);
        verify(processes, never()).save(any());
    }

    @Test
    void aPolicyBeanDecidesWhereTheUpdateGoes() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("policy", (UpdateWithStartPolicy) (definition, key) -> new UpdateWithStartPolicy.Conflict("no reuse"));
        service = new ProcessUpdateService(processes, updates, definitions, create, dispatcher, apply, waiters, logs, metrics,
                beans.getBeanProvider(UpdateWithStartPolicy.class));

        assertThatThrownBy(() -> service.updateWithStart(startRequest("k", "rename", "{\"name\": \"x\"}", null)))
                .hasMessage("no reuse");
    }

    // ── Waiters and the store ────────────────────────────────────────────────────────────────────

    @Test
    void aWaiterIsWokenByAnotherNodesOutcomeThroughThePoll() throws Exception {
        var update = new ProcessUpdate("w-1", "p", "orders", "touch", "w-1", null, null, ProcessUpdate.Status.REQUESTED,
                null, null, null, false, EngineClock.now(), null, null);
        updates.insert(update);
        var future = waiters.await("w-1", Duration.ofSeconds(5));
        assertThat(waiters.waitingCount()).isEqualTo(1);

        updates.save(update.finished(ProcessUpdate.Status.COMPLETED, "1", null, EngineClock.now()));

        assertThat(future.get(5, TimeUnit.SECONDS)).get().extracting(ProcessUpdate::result).isEqualTo("1");
        assertThat(waiters.await("w-1", Duration.ZERO).get()).isPresent();
        assertThat(waiters.await("w-2", Duration.ofMillis(10)).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void theStoreFreesExpiredKeysAndForgetsPurgedProcesses() {
        var now = EngineClock.now();
        updates.insert(new ProcessUpdate("s-1", "p", "orders", "touch", "key", null, null, ProcessUpdate.Status.COMPLETED,
                null, null, null, false, now, now, now.minusSeconds(1)));
        updates.insert(new ProcessUpdate("s-2", "p", "orders", "touch", "other", null, null, ProcessUpdate.Status.COMPLETED,
                null, null, null, false, now.plusSeconds(1), now, now.plusHours(1)));

        assertThat(updates.expireKeys(now)).isEqualTo(1);
        assertThat(updates.findByKey("p", "key")).isEmpty();
        assertThat(updates.findByKey("p", "s-1")).isPresent();
        assertThat(updates.findById("s-1").orElseThrow().expiresAt()).isNull();
        assertThat(updates.findByProcessId("p")).extracting(ProcessUpdate::id).containsExactly("s-1", "s-2");
        assertThat(updates.findFinishedAmong(List.of("s-1", "zz"))).hasSize(1);
        assertThatThrownBy(() -> updates.insert(updates.findById("s-2").orElseThrow()))
                .isInstanceOf(io.mateu.workflow.application.out.ProcessUpdateRepository.DuplicateUpdateException.class);
        assertThat(updates.deleteByProcessIds(List.of("p"))).isEqualTo(2);
        assertThat(updates.findByProcessId("p")).isEmpty();
        var ran = new boolean[1];
        updates.inTransaction(() -> ran[0] = true);
        assertThat(ran[0]).isTrue();
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                     ProcessUpdateRejectedException.Reason reason) {
        assertThatThrownBy(call).isInstanceOf(ProcessUpdateRejectedException.class)
                .extracting(e -> ((ProcessUpdateRejectedException) e).reason()).isEqualTo(reason);
    }
}
