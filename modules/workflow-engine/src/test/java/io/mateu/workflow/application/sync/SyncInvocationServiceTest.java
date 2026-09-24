package io.mateu.workflow.application.sync;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.InlineExecution;
import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.infra.out.memory.InMemoryInvocationRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The service's decisions without an engine: refusals, idempotency, the wait, the lock, the fast-path slot. */
class SyncInvocationServiceTest {

    WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    InvocationRepository invocations = new InMemoryInvocationRepository();
    InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    CreateProcessUseCase create = mock(CreateProcessUseCase.class);
    LockService locks = mock(LockService.class);
    InlineExecution inline = mock(InlineExecution.class);
    InlineExecution.Slot slot = mock(InlineExecution.Slot.class);
    SyncInvocationService service;

    private static final Step START = new Step("start", "wd", StepType.START, "Start", null, null, null, null, false,
            null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    private static final Step REPLY = new Step("reply", "wd", StepType.REPLY, "Reply", null, "start", null, null, false,
            null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);

    private WorkflowDefinition definition(SyncInvocation sync) {
        return new WorkflowDefinition("wd", "WD", 1, null, false, 0, false, null, 0, List.of(START, REPLY))
                .withSyncInvocation(sync);
    }

    @BeforeEach
    void setUp() {
        var providers = new StaticListableBeanFactory();
        providers.addBean("inline", inline);
        service = new SyncInvocationService(definitions, invocations, processes, create,
                new SyncReplyWaiters(processes), WorkflowMetrics.NOOP, WorkflowTracing.NOOP,
                mock(StepExecutionRepository.class), mock(LogMessageRepository.class), locks,
                providers.getBeanProvider(InlineExecution.class));
        service.defaultDeadlineMs = 5000;
        service.maxDeadlineMs = 30000;
        service.retention = Duration.ofHours(1);
        when(definitions.findById("wd")).thenReturn(Optional.of(definition(new SyncInvocation(true, null, null, 0))));
        // The creation "runs": the process appears, as CreateProcessUseCase would make it.
        doAnswer(i -> {
            var command = (io.mateu.workflow.application.usecases.process.create.CreateProcessCommand) i.getArgument(0);
            processes.put(Process.builder().id(command.processId()).businessKey(command.businessKey())
                    .status(ProcessStatus.PENDING).variables(List.of()).build());
            return null;
        }).when(create).handle(any());
        when(inline.reserve(anyString())).thenReturn(slot);
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(slot).claimDuring(any());
    }

    private SyncInvocationService.StartRequest request(String key, Map<String, String> variables, Duration wait) {
        return new SyncInvocationService.StartRequest("wd", key, null, variables, wait, null);
    }

    @Test
    void startsOnceAndARetryJoins_withTheFastPathSlotUsed() {
        var first = service.start(request("k", Map.of("a", "1"), null));
        var second = service.start(request("k", Map.of("a", "1"), Duration.ofSeconds(2)));
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.invocation().processId()).isEqualTo(first.invocation().processId());
        assertThat(first.timeToWait()).isEqualTo(Duration.ofMillis(5000));
        assertThat(second.timeToWait()).isEqualTo(Duration.ofSeconds(2));
        verify(slot).start();
        assertThatThrownBy(() -> service.start(request("k", Map.of("a", "2"), null)))
                .isInstanceOf(SyncInvocationRejectedException.class).hasMessageContaining("different request");
    }

    @Test
    void theWaitIsCappedAndTheDefinitionsDefaultWins() {
        assertThat(service.waitFor(definition(new SyncInvocation(true, null, null, 1500)), null)).isEqualTo(Duration.ofMillis(1500));
        assertThat(service.waitFor(definition(null), Duration.ofMinutes(5))).isEqualTo(Duration.ofMillis(30000));
        assertThat(service.waitFor(null)).isEqualTo(Duration.ZERO);
        assertThat(service.waitFor(Duration.ofMillis(-5))).isEqualTo(Duration.ZERO);
    }

    @Test
    void refusesWhatCannotBeInvoked() {
        when(definitions.findById("mute")).thenReturn(Optional.of(definition(null)));
        assertThatThrownBy(() -> service.start(new SyncInvocationService.StartRequest("mute", "k", null, Map.of(), null, null)))
                .hasMessageContaining("cannot be invoked synchronously");
        when(definitions.findById("off")).thenReturn(Optional.of(definition(new SyncInvocation(true, null, null, 0))
                .withRuntimeStatus(WorkflowStatus.DISABLED)));
        assertThatThrownBy(() -> service.start(new SyncInvocationService.StartRequest("off", "k", null, Map.of(), null, null)))
                .hasMessageContaining("accepts no new instances");
        assertThatThrownBy(() -> service.start(new SyncInvocationService.StartRequest("ghost", "k", null, Map.of(), null, null)))
                .isInstanceOf(UnknownWorkflowDefinitionException.class);
    }

    @Test
    void aTakenBusinessKeyOrADeclinedCreationRollsBackAndGivesTheSlotBack() {
        processes.put(Process.builder().id("x").businessKey("taken").variables(List.of()).build());
        assertThatThrownBy(() -> service.start(new SyncInvocationService.StartRequest("wd", "k1", "taken", Map.of(), null, null)))
                .hasMessageContaining("already exists");
        assertThat(invocations.findByKey("wd", "k1")).isEmpty();

        doAnswer(i -> null).when(create).handle(any()); // declines silently
        assertThatThrownBy(() -> service.start(request("k2", Map.of(), null))).hasMessageContaining("was not created");
        assertThat(invocations.findByKey("wd", "k2")).isEmpty();
        verify(slot, org.mockito.Mockito.atLeast(2)).abandon();
    }

    @Test
    void aBusyLockRefusesWhenTheDefinitionSaysFail() {
        when(definitions.findById("wd")).thenReturn(Optional.of(definition(
                new SyncInvocation(true, null, SyncInvocation.LockBusyPolicy.FAIL, 0)).withProcessLock(new ProcessLock(null, "a"))));
        when(locks.tryAcquire(anyString(), anyString(), anyString())).thenReturn(LockService.Outcome.BUSY);
        assertThatThrownBy(() -> service.start(request("k", Map.of("a", "B1"), null)))
                .isInstanceOf(SyncInvocationRejectedException.class).hasMessageContaining("holds the lock");
        verify(create, never()).handle(any());

        when(locks.tryAcquire(anyString(), anyString(), anyString())).thenReturn(LockService.Outcome.ACQUIRED);
        assertThat(service.start(request("k2", Map.of("a", "B1"), null)).created()).isTrue();
    }

    @Test
    void viewsAndTicksReadTheProcess() {
        var started = service.start(request("k", Map.of(), null));
        var view = service.view(started.invocation());
        assertThat(view.replied()).isFalse();
        assertThat(view.processStatus()).isEqualTo(ProcessStatus.PENDING);
        var tick = service.tick(started.invocation(), new InvocationProgress(null));
        assertThat(tick.processFinished()).isFalse();
        assertThat(tick.events()).extracting(InvocationProgress.Event::name).contains("status");
        var ghost = new Invocation("i", "wd", "k", "h", "no-process", null, null, null, null);
        assertThat(service.view(ghost).processStatus()).isNull();
        assertThat(service.tick(ghost, new InvocationProgress(null)).events()).isEmpty();
        assertThat(service.find(started.invocation().id())).isPresent();
        assertThat(service.findByKey("wd", "k")).isPresent();
    }
}
