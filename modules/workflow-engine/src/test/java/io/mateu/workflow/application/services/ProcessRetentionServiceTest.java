package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.application.sync.Invocation;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Resource;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.TraceContext;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import io.mateu.workflow.infra.out.memory.InMemoryInvocationRepository;
import io.mateu.workflow.infra.out.memory.InMemoryLockService;
import io.mateu.workflow.infra.out.memory.InMemoryLogMessageRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessIndexRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessPurgeRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessTraceContextRepository;
import io.mateu.workflow.infra.out.memory.InMemoryResourceRepository;
import io.mateu.workflow.infra.out.memory.InMemoryStepExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The retention purge's rules, end to end over memory mode's real stores: what is due goes with
 * everything that belongs to it, and everything that is not — still running, a child of a live
 * parent, a step at work or waiting to retry, a lock — stays.
 */
class ProcessRetentionServiceTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);
    static final LocalDateTime LONG_AGO = NOW.minusDays(40);
    static final LocalDateTime RECENTLY = NOW.minusDays(1);

    InMemoryProcessRepository processes;
    InMemoryStepExecutionRepository steps;
    InMemoryLogMessageRepository logs;
    InMemoryResourceRepository resources;
    InMemoryProcessTraceContextRepository traces;
    InMemoryInvocationRepository invocations;
    final io.mateu.workflow.infra.out.memory.InMemoryProcessUpdateRepository updates =
            new io.mateu.workflow.infra.out.memory.InMemoryProcessUpdateRepository();
    InMemoryLockService locks;
    InMemoryProcessIndexRepository index;
    WorkflowDefinitionRepository definitions;
    List<String> metrics;
    ProcessRetentionProperties properties;

    @BeforeEach
    void setUp() {
        processes = new InMemoryProcessRepository();
        var events = mock(ProcessDomainEventUseCase.class);
        ReflectionTestUtils.setField(processes, "processDomainEventUseCase", events);
        ReflectionTestUtils.setField(processes, "processStatusAnnouncer", new ProcessStatusAnnouncer(false, null));
        steps = new InMemoryStepExecutionRepository();
        ReflectionTestUtils.setField(steps, "processDomainEventUseCase", events);
        logs = new InMemoryLogMessageRepository();
        resources = new InMemoryResourceRepository();
        traces = new InMemoryProcessTraceContextRepository();
        invocations = new InMemoryInvocationRepository();
        locks = new InMemoryLockService();
        ReflectionTestUtils.setField(locks, "leaseMs", 60_000L);
        index = new InMemoryProcessIndexRepository();
        definitions = mock(WorkflowDefinitionRepository.class);
        metrics = new ArrayList<>();
        properties = new ProcessRetentionProperties();
        definitions(definition("orders", ProcessRetention.of("P30D")));
    }

    void definitions(WorkflowDefinition... all) {
        when(definitions.findAll()).thenReturn(List.of(all));
    }

    static WorkflowDefinition definition(String id, ProcessRetention retention) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of()).withRetention(retention);
    }

    ProcessRetentionService service() {
        var purge = new InMemoryProcessPurgeRepository(processes, steps, logs, resources, traces, invocations, locks);
        purge.setUpdates(updates);
        var beans = new StaticListableBeanFactory();
        beans.addBean("index", index);
        ObjectProvider<ProcessIndexRepository> indexProvider = beans.getBeanProvider(ProcessIndexRepository.class);
        ObjectProvider<ProcessTrace> traceProvider = beans.getBeanProvider(ProcessTrace.class);
        WorkflowMetrics recording = new WorkflowMetrics() {
            @Override
            public void processesPurged(String workflowDefinitionId, ProcessStatus status, int count) {
                metrics.add(workflowDefinitionId + "/" + status + "=" + count);
            }
        };
        return new ProcessRetentionService(purge, definitions, recording, indexProvider, traceProvider, properties);
    }

    Process process(String id, String definitionId, ProcessStatus status, LocalDateTime finished, String parentStepExecutionId) {
        var process = Process.builder().id(id).businessKey("bk-" + id).workflowDefinitionId(definitionId)
                .status(status).finished(finished).parentStepExecutionId(parentStepExecutionId)
                .variables(List.of()).build();
        processes.save(process);
        index.upsert(new ProcessIndexRow(id, "bk-" + id, id, definitionId, 1, status.name(), 100,
                null, null, finished, finished, null));
        return process;
    }

    String step(String processId, String stepId, StepExecutionStatus status) {
        var id = processId + "-" + stepId;
        steps.save(StepExecution.builder().id(id).processId(processId).stepId(stepId).status(status).build());
        return id;
    }

    /** A finished process with one of everything that belongs to it. */
    void finished(String id, String definitionId, ProcessStatus status, LocalDateTime finished) {
        process(id, definitionId, status, finished, null);
        step(id, "a", StepExecutionStatus.COMPLETED);
        logs.save(LogMessage.builder().id(id + "-log").processId(id).message("hi").build());
        resources.save(Resource.builder().id(id + "-res").processId(id).name("r").build());
        traces.save(id, TraceContext.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null, null));
        invocations.createWith(new Invocation(id + "-inv", definitionId, "key-" + id, null, id, null, NOW, NOW.plusDays(1), null),
                () -> { });
        updates.insert(new io.mateu.workflow.application.updates.ProcessUpdate(id + "-upd", id, definitionId, "rename",
                "key-" + id, null, null, io.mateu.workflow.application.updates.ProcessUpdate.Status.COMPLETED, "{}", null,
                null, false, NOW, NOW, NOW.plusDays(1)));
    }

    boolean gone(String id) {
        return processes.findById(id).isEmpty()
                && steps.findByProcessId(id).isEmpty()
                && logs.findByProcessId(id).isEmpty()
                && resources.findByProcessId(id).isEmpty()
                && traces.findByProcessId(id).isEmpty()
                && invocations.findByProcessId(id).isEmpty()
                && updates.findByProcessId(id).isEmpty()
                && index.findByProcessId(id).isEmpty();
    }

    @Test
    void aFinishedProcessPastItsRetentionGoesWithEverythingThatBelongsToIt() {
        finished("old", "orders", ProcessStatus.COMPLETED, LONG_AGO);
        finished("new", "orders", ProcessStatus.COMPLETED, RECENTLY);

        var report = service().purgeDue(NOW);

        assertThat(gone("old")).isTrue();
        assertThat(processes.findById("new")).isPresent();
        assertThat(steps.findByProcessId("new")).hasSize(1);
        assertThat(index.findByProcessId("new")).isPresent();
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.purged()).isEqualTo(Map.of("orders", Map.of(ProcessStatus.COMPLETED, 1)));
        assertThat(metrics).containsExactly("orders/COMPLETED=1");
        // Its business key is free again.
        assertThat(processes.findByBusinessKey("bk-old")).isEmpty();
    }

    @Test
    void everyTerminalStatusIsPurgeableErrorIncludedAndNothingThatHasNotEnded() {
        for (var status : ProcessStatus.values()) {
            process("p-" + status, "orders", status, LONG_AGO, null);
        }
        service().purgeDue(NOW);

        for (var status : ProcessRetention.TERMINAL) {
            assertThat(processes.findById("p-" + status)).as(status.name()).isEmpty();
        }
        for (var status : List.of(ProcessStatus.PENDING, ProcessStatus.RUNNING, ProcessStatus.PAUSED)) {
            assertThat(processes.findById("p-" + status)).as(status.name()).isPresent();
        }
    }

    @Test
    void nothingIsPurgedWithoutARetentionAndForeverKeepsWhateverTheDefaultsSay() {
        definitions(definition("orders", null), definition("audit", ProcessRetention.of("forever")));
        finished("o", "orders", ProcessStatus.COMPLETED, LONG_AGO);
        finished("a", "audit", ProcessStatus.COMPLETED, LONG_AGO);

        assertThat(service().purgeDue(NOW).total()).isZero();
        assertThat(processes.findAll()).hasSize(2);

        properties.setRetention(Duration.ofDays(7));
        service().purgeDue(NOW);
        assertThat(gone("o")).isTrue();
        assertThat(processes.findById("a")).isPresent();
    }

    @Test
    void perStatusRetentionsAndTheProcessesOfADeletedDefinitionFallBackToTheDefaults() {
        definitions(definition("orders", ProcessRetention.perStatus(Map.of("error", "P180D"))));
        process("completed", "orders", ProcessStatus.COMPLETED, LONG_AGO, null);
        process("error", "orders", ProcessStatus.ERROR, LONG_AGO, null);
        process("orphan", "deleted-definition", ProcessStatus.COMPLETED, LONG_AGO, null);

        service().purgeDue(NOW);
        assertThat(processes.findAll()).hasSize(3);

        properties.getRetentionByStatus().put("completed", Duration.ofDays(30));
        service().purgeDue(NOW);
        assertThat(processes.findById("completed")).isEmpty();
        assertThat(processes.findById("orphan")).isEmpty();
        // ERROR is the definition's: 180 days, not yet.
        assertThat(processes.findById("error")).isPresent();
    }

    @Test
    void aProcessTreeGoesTogetherAndOnlyWhenEveryProcessInItHasEnded() {
        definitions(definition("orders", ProcessRetention.of("P30D")), definition("payments", ProcessRetention.of("forever")));
        // Root → child (another definition, which keeps forever: the root's retention decides) → grandchild.
        process("root", "orders", ProcessStatus.COMPLETED, LONG_AGO, null);
        var rootCall = step("root", "pay", StepExecutionStatus.COMPLETED);
        process("child", "payments", ProcessStatus.COMPLETED, LONG_AGO, rootCall);
        var childCall = step("child", "refund", StepExecutionStatus.COMPLETED);
        process("grandchild", "orders", ProcessStatus.ERROR, LONG_AGO, childCall);

        // A second tree whose child is still running stays whole.
        process("busy-root", "orders", ProcessStatus.COMPLETED, LONG_AGO, null);
        var busyCall = step("busy-root", "pay", StepExecutionStatus.COMPLETED);
        process("busy-child", "orders", ProcessStatus.RUNNING, null, busyCall);

        // A child that is past its own retention while its parent is still running is not touched.
        process("live-parent", "orders", ProcessStatus.RUNNING, null, null);
        var liveCall = step("live-parent", "pay", StepExecutionStatus.COMPLETED);
        process("done-child", "orders", ProcessStatus.COMPLETED, LONG_AGO, liveCall);

        var report = service().purgeDue(NOW);

        assertThat(processes.findById("root")).isEmpty();
        assertThat(processes.findById("child")).isEmpty();
        assertThat(processes.findById("grandchild")).isEmpty();
        assertThat(steps.findById(rootCall)).isEmpty();
        assertThat(processes.findById("busy-root")).isPresent();
        assertThat(processes.findById("busy-child")).isPresent();
        assertThat(processes.findById("live-parent")).isPresent();
        assertThat(processes.findById("done-child")).isPresent();
        assertThat(report.treesKept()).isEqualTo(1);
        assertThat(metrics).containsExactlyInAnyOrder("orders/COMPLETED=1", "orders/ERROR=1", "payments/COMPLETED=1");
    }

    @Test
    void aStepAtWorkOrWaitingToRetryOrALockKeepsTheProcess() {
        definitions(definition("orders", ProcessRetention.of("P30D")));
        process("retrying", "orders", ProcessStatus.ERROR, LONG_AGO, null);
        step("retrying", "a", StepExecutionStatus.AWAITING_RETRY);
        process("running-step", "orders", ProcessStatus.CANCELLED, LONG_AGO, null);
        step("running-step", "a", StepExecutionStatus.RUNNING);
        process("holder", "orders", ProcessStatus.COMPLETED, LONG_AGO, null);
        locks.acquire("booking", "42", "holder", "holder-lock");
        process("waiter", "orders", ProcessStatus.COMPLETED, LONG_AGO, null);
        locks.acquire("booking", "42", "waiter", "waiter-lock");
        finished("free", "orders", ProcessStatus.COMPLETED, LONG_AGO);

        var report = service().purgeDue(NOW);

        assertThat(processes.findAll()).extracting(Process::getId)
                .containsExactlyInAnyOrder("retrying", "running-step", "holder", "waiter");
        assertThat(report.treesKept()).isEqualTo(4);
        assertThat(report.total()).isEqualTo(1);
    }

    @Test
    void aPassStopsAtItsBudgetAndPagesPastWhatItHadToKeep() {
        properties.getPurge().setBatchSize(2);
        properties.getPurge().setMaxPerPass(3);
        // The oldest two are kept (a step at work), so a pass must page past them.
        process("kept-1", "orders", ProcessStatus.ERROR, LONG_AGO.minusDays(2), null);
        step("kept-1", "a", StepExecutionStatus.AWAITING_RETRY);
        process("kept-2", "orders", ProcessStatus.ERROR, LONG_AGO.minusDays(2), null);
        step("kept-2", "a", StepExecutionStatus.AWAITING_RETRY);
        for (int i = 0; i < 5; i++) {
            process("done-" + i, "orders", ProcessStatus.ERROR, LONG_AGO.plusMinutes(i), null);
        }

        assertThat(service().purgeDue(NOW).total()).isEqualTo(3);
        assertThat(processes.findById("done-0")).isEmpty();
        assertThat(processes.findById("done-3")).isPresent();
        assertThat(service().purgeDue(NOW).total()).isEqualTo(2);
        assertThat(processes.findAll()).extracting(Process::getId).containsExactlyInAnyOrder("kept-1", "kept-2");
    }

    @Test
    void theStoreRefusesAProcessThatWasRetriedAfterItWasFound() {
        process("retried", "orders", ProcessStatus.ERROR, LONG_AGO, null);
        var purge = new InMemoryProcessPurgeRepository(processes, steps, logs, resources, traces, invocations, locks);
        var found = purge.findRoots("orders", ProcessStatus.ERROR, NOW.minusDays(30), null, 10);
        assertThat(found).hasSize(1);

        // An operator retries it in between.
        processes.save(processes.findById("retried").orElseThrow().withStatus(ProcessStatus.RUNNING).withFinished(null));

        assertThat(purge.purge(found)).isFalse();
        assertThat(processes.findById("retried")).isPresent();
        assertThat(purge.purge(List.of())).isFalse();
        assertThat(purge.knownWorkflowDefinitionIds()).containsExactly("orders");
    }
}
