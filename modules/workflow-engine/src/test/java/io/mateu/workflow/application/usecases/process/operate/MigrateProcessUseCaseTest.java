package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.analysis.MigrationRules;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import io.mateu.workflow.dtos.events.domain.ProcessMigrated;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import io.mateu.workflow.support.TrackedProcessLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The migration use case and its planner over mocks: what moves where, what is refused, what is recorded. */
public class MigrateProcessUseCaseTest {

    final ProcessRepository processes = mock(ProcessRepository.class);
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final ProcessLockService processLock = mock(ProcessLockService.class);
    final DownstreamEventPublisher downstream = mock(DownstreamEventPublisher.class);
    final StepOverProcessUseCase stepOver = mock(StepOverProcessUseCase.class);
    final MessageSubscriptionService subscriptions = mock(MessageSubscriptionService.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final WorkflowDefinitionVersionEntityRepository history = mock(WorkflowDefinitionVersionEntityRepository.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<WorkflowDefinitionVersionEntityRepository> versions = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<CancelProcessUseCase> cancel = mock(ObjectProvider.class);
    final LogMessageRepository logs = mock(LogMessageRepository.class);
    final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    final ProcessOperationRules rules = new ProcessOperationRules(new CompensationService());
    final MigrationPlanner planner = new MigrationPlanner(definitions, versions, rules, 0);
    final TrackedProcessLock lock = new TrackedProcessLock();
    final List<String> lines = new ArrayList<>();
    final List<StepExecution> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(processLock.runExclusively(anyString(), any())).thenAnswer(lock.granted());
        when(logs.save(any())).thenAnswer(invocation -> {
            LogMessage line = invocation.getArgument(0);
            lines.add(line.getMessage());
            return line.getId();
        });
        when(steps.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return "ok";
        });
    }

    MigrateProcessUseCase useCase(boolean announce) {
        return new MigrateProcessUseCase(processes, steps, processLock, downstream, stepOver, planner,
                new OperatorAudit(logs, metrics), new ProcessStatusAnnouncer(announce, ""), subscriptions, cancel);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    public static Map<String, Object> step(String id, String type, String precondition, Object... more) {
        var step = new LinkedHashMap<String, Object>();
        step.put("id", id);
        step.put("type", type);
        step.put("name", id);
        step.put("workflowDefinitionId", "w");
        if (precondition != null) {
            step.put("preconditionStepId", precondition);
        }
        for (int i = 0; i < more.length; i += 2) {
            step.put((String) more[i], more[i + 1]);
        }
        return step;
    }

    public static WorkflowDefinition definition(int version, List<Map<String, Object>> steps) {
        var json = new LinkedHashMap<String, Object>();
        json.put("id", "w");
        json.put("name", "W");
        json.put("version", version);
        json.put("steps", steps);
        return pojoFromJson(toJson(json), WorkflowDefinition.class);
    }

    public static List<Map<String, Object>> v1() {
        return List.of(step("start", "START", null), step("a", "ACTION", "start", "topic", "work"),
                step("b", "ACTION", "a", "topic", "work"), step("c", "ACTION", "b", "topic", "work"),
                step("end", "END", "c"));
    }

    public static StepExecution execution(Map<String, Object> step, StepExecutionStatus status, long order) {
        var id = (String) step.get("id");
        return StepExecution.builder().id("se-" + id).processId("p").workflowDefinitionId("w").stepId(id)
                .stepJson(toJson(step)).status(status).order(order)
                .startedAt(status == StepExecutionStatus.CREATED ? null : LocalDateTime.now().minusSeconds(5))
                .finishedAt(status.isTerminal() ? LocalDateTime.now() : null).variables(List.of()).build();
    }

    Process process(ProcessStatus status, WorkflowDefinition definition, Variable... variables) {
        var process = Process.builder().id("p").workflowDefinitionId("w").workflowDefinitionVersion(definition.version())
                .workflowDefinitionJson(toJson(definition)).status(status)
                .variables(new ArrayList<>(List.of(variables))).build();
        when(processes.findById("p")).thenReturn(Optional.of(process));
        return process;
    }

    void stepsAre(StepExecution... executions) {
        when(steps.findByProcess(any())).thenReturn(List.of(executions));
        when(steps.findByProcessId("p")).thenReturn(List.of(executions));
    }

    Process savedProcess() {
        var captor = ArgumentCaptor.forClass(Process.class);
        verify(processes).save(captor.capture());
        return captor.getValue();
    }

    // ── the use case ─────────────────────────────────────────────────────────────────────────────

    @Test
    void remapsKeepsTheTaskAddsAndDropsThenStepsOverAndRecords() {
        process(ProcessStatus.RUNNING, definition(1, v1()));
        var a = execution(v1().get(1), StepExecutionStatus.COMPLETED, 2);
        var b = execution(v1().get(2), StepExecutionStatus.PENDING, 3);
        var c = execution(v1().get(3), StepExecutionStatus.CREATED, 4);
        stepsAre(execution(v1().get(0), StepExecutionStatus.COMPLETED, 1), a, b, c,
                execution(v1().get(4), StepExecutionStatus.CREATED, 5));
        var v2 = definition(2, List.of(step("start", "START", null), step("a", "ACTION", "start", "topic", "work"),
                step("b", "ACTION", "a", "topic", "work", "timeout", 60000), step("d", "ACTION", "b"),
                step("end", "END", "d")));
        when(definitions.findById("w")).thenReturn(Optional.of(v2));

        useCase(true).handle(new MigrateProcessCommand("p", null, null, Map.of("note", "x"), "fix", "bob"));

        var after = savedProcess();
        assertThat(after.getWorkflowDefinitionVersion()).isEqualTo(2);
        assertThat(pojoFromJson(after.getWorkflowDefinitionJson(), WorkflowDefinition.class).steps())
                .extracting(s -> s.id()).containsExactly("start", "a", "b", "d", "end");
        assertThat(after.getVariables()).contains(new Variable("note", "x"));
        assertThat(after.popEvents()).hasAtLeastOneElementOfType(ProcessMigrated.class)
                .hasAtLeastOneElementOfType(ProcessStatusChanged.class);
        assertThat(b.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(b.getDeadlineAt()).isNotNull();
        assertThat(c.isSuperseded()).isTrue();
        assertThat(c.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(saved).anySatisfy(se -> {
            assertThat(se.getStepId()).isEqualTo("d");
            assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
            assertThat(se.getOrder()).isEqualTo(4);
        });
        verify(downstream, never()).publish(any(), any());
        verify(stepOver).handle(any());
        verify(subscriptions).rearm(any());
        verify(metrics).processOperation("w", OperatorAudit.MIGRATE);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("[migrate] by bob: migrated from v1 to v2")
                .contains("keep task [b]").contains("add [d]").contains("drop [c]").contains("set [note = 'x']")
                .contains("reason: fix"));
    }

    @Test
    void aChangedContractIsCancelledAndDispatchedAgain() {
        process(ProcessStatus.PAUSED, definition(1, v1()));
        var b = execution(v1().get(2), StepExecutionStatus.RUNNING, 3);
        var dispatches = b.getDispatchCount();
        stepsAre(execution(v1().get(0), StepExecutionStatus.COMPLETED, 1),
                execution(v1().get(1), StepExecutionStatus.COMPLETED, 2), b,
                execution(v1().get(3), StepExecutionStatus.CREATED, 4), execution(v1().get(4), StepExecutionStatus.CREATED, 5));
        var v2 = definition(2, List.of(step("start", "START", null), step("a", "ACTION", "start", "topic", "work"),
                step("b2", "HTTP_CALL", "a", "http", Map.of("url", "https://example.org", "method", "POST")),
                step("c", "ACTION", "b2", "topic", "work"), step("end", "END", "c")));
        when(definitions.findById("w")).thenReturn(Optional.of(v2));

        useCase(false).handle(new MigrateProcessCommand("p", 2, Map.of("b", "b2"), null, "fix", null));

        verify(downstream).publish(any(TaskCancellationRequested.class), eq("work"));
        assertThat(b.getStepId()).isEqualTo("b2");
        assertThat(b.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(b.getDispatchCount()).isGreaterThan(dispatches);
        // Paused: nothing steps it over until it is resumed.
        verify(stepOver, never()).handle(any());
        verify(subscriptions, never()).rearm(any());
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("by anonymous").contains("mapping [b→b2]")
                .contains("redispatch [b→b2]").contains("warnings:"));
    }

    @Test
    void aRefusedPlanChangesNothingAndIsRecorded() {
        process(ProcessStatus.RUNNING, definition(1, v1()));
        stepsAre(execution(v1().get(0), StepExecutionStatus.COMPLETED, 1),
                execution(v1().get(1), StepExecutionStatus.COMPLETED, 2),
                execution(v1().get(2), StepExecutionStatus.PENDING, 3));
        when(definitions.findById("w")).thenReturn(Optional.of(definition(2, List.of(step("start", "START", null),
                step("a", "ACTION", "start"), step("b", "TIMER", "a", "duration", 1000)))));

        useCase(false).handle(new MigrateProcessCommand("p", null, null, null, "no", "bob"));

        verify(processes, never()).save(any());
        verify(steps, never()).save(any());
        assertThat(lines).singleElement().asString().contains("NOT carried out").contains("'b' is ACTIVE");
    }

    @Test
    void aVersionThatIsNotThereIsRefusedAndAMissingProcessIgnored() {
        process(ProcessStatus.RUNNING, definition(1, v1()));
        stepsAre();
        when(definitions.findById("w")).thenReturn(Optional.of(definition(1, v1())));
        useCase(false).handle(new MigrateProcessCommand("p", 4, null, null, "no", "bob"));
        assertThat(lines).singleElement().asString().contains("migrate to v4").contains("keeps no version history");

        when(processes.findById("p")).thenReturn(Optional.empty());
        useCase(false).handle(new MigrateProcessCommand("p", null, null, null, "no", "bob"));
        assertThat(lines).hasSize(1);
        verify(processes, never()).save(any());
    }

    @Test
    void historyContainersAndEarlierPassesFollowTheirStep() {
        var forEach = step("each", "ACTION", "start", "topic", "work", "forEach", Map.of("items", "xs"));
        var source = List.of(step("start", "START", null), forEach, step("poll", "ACTION", "each", "topic", "work"),
                step("gone", "ACTION", "start", "topic", "work"), step("end", "END", "poll"));
        process(ProcessStatus.RUNNING, definition(1, source), new Variable("xs", "[1,2]"));
        var container = execution(forEach, StepExecutionStatus.PENDING, 2).toBuilder()
                .scopeJson("{\"total\":2}").build();
        var done = StepExecution.iterationOf(container, toJson(forEach), 0, Map.of("item", "1"));
        done.cancelQuietly();
        var atWorker = StepExecution.iterationOf(container, toJson(forEach), 1, Map.of("item", "2")).toBuilder()
                .status(StepExecutionStatus.RUNNING).startedAt(LocalDateTime.now()).build();
        var discarded = StepExecution.iterationOf(container, toJson(forEach), 0, Map.of("item", "1"));
        discarded.supersede(null);
        var earlierPass = execution(source.get(2), StepExecutionStatus.COMPLETED, 3).toBuilder().id("se-poll-0")
                .superseded(true).build();
        var gone = execution(source.get(3), StepExecutionStatus.COMPLETED, 4);
        stepsAre(execution(source.get(0), StepExecutionStatus.COMPLETED, 1), container, done, atWorker, discarded,
                earlierPass, execution(source.get(2), StepExecutionStatus.CREATED, 3), gone,
                execution(source.get(4), StepExecutionStatus.CREATED, 5));
        var target = definition(2, List.of(step("start", "START", null),
                step("each", "ACTION", "start", "topic", "work-v2", "forEach", Map.of("items", "xs")),
                step("check", "ACTION", "each", "topic", "work"), step("end", "END", "check")));
        when(definitions.findById("w")).thenReturn(Optional.of(target));

        useCase(false).handle(new MigrateProcessCommand("p", null, Map.of("poll", "check"), null, "fix", "bob"));

        assertThat(container.getStepId()).isEqualTo("each");
        assertThat(atWorker.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        verify(downstream).publish(any(TaskCancellationRequested.class), eq("work"));
        assertThat(atWorker.topic()).isEqualTo("work-v2");
        assertThat(done.topic()).isEqualTo("work-v2");
        assertThat(earlierPass.getStepId()).isEqualTo("check");
        assertThat(gone.isSuperseded()).isTrue();
        assertThat(gone.getLoopDecision()).isEqualTo(StepExecution.LOOP_DISCARDED);
        verify(steps, atLeastOnce()).save(discarded);
    }

    @Test
    void aWaitingMessageIsReSubscribed() {
        var wait = step("wait", "WAIT_FOR_MESSAGE", "start", "messageName", "paid", "correlationExpression", "orderId");
        var source = List.of(step("start", "START", null), wait, step("end", "END", "wait"));
        process(ProcessStatus.RUNNING, definition(1, source), new Variable("orderId", "O-1"), new Variable("inv", "I-1"));
        var waiting = execution(wait, StepExecutionStatus.CREATED, 2);
        waiting.start(processes.findById("p").orElseThrow());
        waiting.popEvents();
        stepsAre(execution(source.get(0), StepExecutionStatus.COMPLETED, 1), waiting,
                execution(source.get(2), StepExecutionStatus.CREATED, 3));
        when(definitions.findById("w")).thenReturn(Optional.of(definition(2, List.of(step("start", "START", null),
                step("wait", "WAIT_FOR_MESSAGE", "start", "messageName", "settled", "correlationExpression", "inv"),
                step("end", "END", "wait")))));

        useCase(false).handle(new MigrateProcessCommand("p", null, null, null, "rename", "bob"));

        assertThat(waiting.getAwaitingMessageName()).isEqualTo("settled");
        assertThat(waiting.getAwaitingCorrelationKey()).isEqualTo("I-1");
        assertThat(waiting.popEvents()).filteredOn(MessageSubscriptionChanged.class::isInstance)
                .extracting(e -> ((MessageSubscriptionChanged) e).waiting()).containsExactly(false, true);
    }

    // ── the planner ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theTargetComesFromTheHistoryWhenThereIsOne() {
        when(definitions.findById("w")).thenReturn(Optional.of(definition(3, v1())));
        when(versions.getIfAvailable()).thenReturn(history);
        var entity = mock(WorkflowDefinitionVersionEntity.class);
        when(entity.getSnapshotJson()).thenReturn(toJson(definition(2, v1())));
        when(entity.getVersion()).thenReturn(2);
        when(history.findByDefinitionIdAndVersion("w", 2)).thenReturn(Optional.of(entity));
        when(history.findByDefinitionIdOrderByVersionDesc("w")).thenReturn(List.of(entity));

        assertThat(planner.target("w", 2).version()).isEqualTo(2);
        assertThat(planner.target("w", 3).version()).isEqualTo(3);
        assertThat(planner.target("w", null).steps()).allSatisfy(s -> assertThat(s.workflowDefinitionId()).isEqualTo("w"));
        assertThat(planner.versions("w")).containsExactly(3, 2);
        assertThatThrownBy(() -> planner.target("w", 9)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no version 9");
        assertThatThrownBy(() -> planner.target("nope", null)).isInstanceOf(UnknownWorkflowDefinitionException.class);
        when(versions.getIfAvailable()).thenReturn(null);
        assertThat(planner.versions("w")).containsExactly(3);
    }

    @Test
    void theStatesTheRulesAreGiven() {
        var lock = step("lock", "LOCK", "start", "lockName", "l", "lockKey", "k");
        var unlock = step("unlock", "UNLOCK", "lock", "lockName", "l", "lockKey", "k");
        var routed = step("t", "ACTION", "start", "timeout", 1000, "onTimeoutStepId", "lock");
        var held = execution(lock, StepExecutionStatus.COMPLETED, 1);
        var queued = execution(lock, StepExecutionStatus.WAITING_ON_LOCK, 1);
        var open = execution(unlock, StepExecutionStatus.CREATED, 2);
        var closed = execution(unlock, StepExecutionStatus.COMPLETED, 2);

        assertThat(MigrationPlanner.state(held, List.of(held, open)).holdsLock()).isTrue();
        assertThat(MigrationPlanner.state(held, List.of(held, closed)).holdsLock()).isFalse();
        assertThat(MigrationPlanner.state(queued, List.of(queued)).waitingOnLock()).isTrue();
        assertThat(MigrationPlanner.state(execution(routed, StepExecutionStatus.TIMEOUT, 3), List.of()).state())
                .isEqualTo(MigrationRules.State.SKIPPED);
        assertThat(MigrationPlanner.state(execution(v1().get(1), StepExecutionStatus.TIMEOUT, 3), List.of()).state())
                .isEqualTo(MigrationRules.State.FAILED);
        assertThat(MigrationPlanner.state(execution(v1().get(1), StepExecutionStatus.ERROR, 3), List.of()).state())
                .isEqualTo(MigrationRules.State.FAILED);
        assertThat(MigrationPlanner.state(execution(v1().get(1), StepExecutionStatus.CANCELLED, 3), List.of()).state())
                .isEqualTo(MigrationRules.State.SKIPPED);
        var atWorker = MigrationPlanner.state(execution(v1().get(1), StepExecutionStatus.PENDING, 3), List.of());
        assertThat(atWorker.atWorker()).isTrue();
        assertThat(atWorker.state()).isEqualTo(MigrationRules.State.ACTIVE);
        var injected = execution(v1().get(1), StepExecutionStatus.AWAITING_RETRY, 3).toBuilder()
                .injectedByStepExecutionId("dyn").build();
        assertThat(MigrationPlanner.state(injected, List.of()).injected()).isTrue();
        assertThat(MigrationPlanner.state(injected.toBuilder().status(null).build(), List.of()).state())
                .isEqualTo(MigrationRules.State.NOT_STARTED);
        assertThat(MigrationPlanner.snapshot(Process.builder().workflowDefinitionJson("{not json").build())).isNull();
        assertThat(MigrationPlanner.snapshot(Process.builder().build())).isNull();
        assertThat(MigrationPlanner.generic("[1]")).isEmpty();
        assertThat(MigrationPlanner.generic(null)).isEmpty();
    }

    @Test
    void aPlanForAProcessWithoutSnapshotUsesItsExecutions() {
        var process = Process.builder().id("p").workflowDefinitionId("w").status(ProcessStatus.RUNNING)
                .variables(List.of()).build();
        when(definitions.findById("w")).thenReturn(Optional.of(definition(2, v1())));
        var planned = planner.plan(process, List.of(execution(v1().get(1), StepExecutionStatus.COMPLETED, 1)), null,
                null, null);
        assertThat(planned.fromVersion()).isZero();
        assertThat(planned.plan().ofSource("a").action()).isEqualTo(MigrationRules.Action.REMAP);
        assertThat(planned.position("nope")).isEqualTo(6);
        assertThat(planned.targetStep("nope")).isNull();
    }
}
