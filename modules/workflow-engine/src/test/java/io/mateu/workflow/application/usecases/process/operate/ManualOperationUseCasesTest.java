package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.support.TrackedProcessLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.mateu.workflow.application.usecases.process.operate.ProcessOperationRulesTest.se;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The three manual-operation use cases over mocks: what they change, what they refuse, what they record. */
class ManualOperationUseCasesTest {

    final ProcessRepository processes = mock(ProcessRepository.class);
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final ProcessLockService processLock = mock(ProcessLockService.class);
    final DownstreamEventPublisher downstream = mock(DownstreamEventPublisher.class);
    final MessageSubscriptionService subscriptions = mock(MessageSubscriptionService.class);
    final StepOverProcessUseCase stepOver = mock(StepOverProcessUseCase.class);
    final LockService locks = mock(LockService.class);
    final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    final LogMessageRepository logs = mock(LogMessageRepository.class);
    final ProcessOperationRules rules = new ProcessOperationRules(new CompensationService());
    final OperatorAudit audit = new OperatorAudit(logs, metrics);
    @SuppressWarnings("unchecked")
    final ObjectProvider<CancelProcessUseCase> cancel = mock(ObjectProvider.class);
    final TrackedProcessLock lock = new TrackedProcessLock();
    final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(processLock.runExclusively(anyString(), any())).thenAnswer(lock.granted());
        when(logs.save(any())).thenAnswer(invocation -> {
            lock.assertHeld("the audit line");
            LogMessage line = invocation.getArgument(0);
            lines.add(line.getWorkerId() + " " + line.getMessage());
            return line.getId();
        });
    }

    Process process(ProcessStatus status, Variable... variables) {
        var process = Process.builder().id("p").workflowDefinitionId("w").status(status)
                .variables(new ArrayList<>(List.of(variables))).build();
        when(processes.findById("p")).thenReturn(Optional.of(process));
        return process;
    }

    void stepsAre(StepExecution... executions) {
        when(steps.findByProcess(any())).thenReturn(List.of(executions));
        when(steps.findByProcessId("p")).thenReturn(List.of(executions));
        for (var execution : executions) {
            when(steps.findById(execution.getId())).thenReturn(Optional.of(execution));
        }
    }

    // ── Set variables ────────────────────────────────────────────────────────────────────────────

    SetProcessVariablesUseCase setVariables() {
        return new SetProcessVariablesUseCase(processes, processLock, subscriptions, stepOver, rules, audit);
    }

    @Test
    void setVariablesWritesRemovesRearmsAndSteps() {
        var process = process(ProcessStatus.RUNNING, new Variable("a", "1"), new Variable("b", "2"));

        setVariables().handle(new SetProcessVariablesCommand("p", List.of(new Variable("a", "9"), new Variable("c", "3")),
                List.of("b", "zz"), "fix", "alice"));

        assertThat(process.getVariables()).containsExactlyInAnyOrder(new Variable("a", "9"), new Variable("c", "3"));
        verify(processes).save(process);
        verify(subscriptions).rearm(process);
        verify(stepOver).handle(any());
        verify(metrics).processOperation("w", OperatorAudit.SET_VARIABLES);
        assertThat(lines).singleElement().asString().startsWith("operator:alice [set-variables] by alice:")
                .contains("set a = '9' (was '1')", "added c = '3'", "removed b (was '2')", "reason: fix");
    }

    @Test
    void setVariablesOnAFinishedProcessIsRefusedAndRecorded() {
        var process = process(ProcessStatus.COMPLETED, new Variable("a", "1"));

        setVariables().handle(new SetProcessVariablesCommand("p", List.of(new Variable("a", "2")), List.of(), "x", null));

        assertThat(process.getVariables()).containsExactly(new Variable("a", "1"));
        verify(processes, never()).save(any());
        assertThat(lines).singleElement().asString().contains("operator:anonymous", "NOT carried out", "COMPLETED");
    }

    @Test
    void setVariablesThatChangeNothingRecordNothing() {
        process(ProcessStatus.PAUSED, new Variable("a", "1"));
        setVariables().handle(new SetProcessVariablesCommand("p", List.of(new Variable("a", "1")), List.of("nope"), "x", "a"));
        verify(processes, never()).save(any());
        assertThat(lines).isEmpty();
        when(processes.findById("p")).thenReturn(Optional.empty());
        setVariables().handle(new SetProcessVariablesCommand("p", List.of(), List.of(), "x", "a"));
        assertThat(lines).isEmpty();
    }

    // ── Complete step ────────────────────────────────────────────────────────────────────────────

    CompleteStepManuallyUseCase completeStep() {
        return new CompleteStepManuallyUseCase(processes, steps, processLock, downstream, subscriptions, stepOver, rules,
                audit, cancel);
    }

    @Test
    void completingARunningStepCancelsItsWorkerAndReopensTheProcess() {
        var process = process(ProcessStatus.ERROR);
        var running = se("a", "ACTION", Map.of(), StepExecutionStatus.RUNNING);
        var failed = se("b", "ACTION", Map.of(), StepExecutionStatus.ERROR);
        stepsAre(running, failed);

        completeStep().handle(new CompleteStepManuallyCommand("p", "se-b", List.of(new Variable("ref", "R")), "phone", "bob"));

        assertThat(failed.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(saved.getValue().getFinished()).isNull();
        assertThat(saved.getValue().getVariables()).contains(new Variable("ref", "R"));
        assertThat(lines).singleElement().asString().contains("completed step b (was ERROR)", "running again");

        completeStep().handle(new CompleteStepManuallyCommand("p", "se-a", List.of(), "stuck", "bob"));
        verify(downstream).publish(eq(new TaskCancellationRequested("se-a")), isNull());
        assertThat(running.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void anotherFailureKeepsTheProcessFailed() {
        process(ProcessStatus.ERROR);
        var a = se("a", "ACTION", Map.of(), StepExecutionStatus.ERROR);
        var b = se("b", "ACTION", Map.of(), StepExecutionStatus.ERROR);
        stepsAre(a, b);

        completeStep().handle(new CompleteStepManuallyCommand("p", "se-a", List.of(), "x", "bob"));

        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void completeStepRefusalsAndNoOps() {
        process(ProcessStatus.RUNNING);
        var done = se("d", "ACTION", Map.of(), StepExecutionStatus.COMPLETED);
        var created = se("c", "ACTION", Map.of(), StepExecutionStatus.CREATED);
        stepsAre(done, created);

        completeStep().handle(new CompleteStepManuallyCommand("p", "se-d", List.of(), "x", "bob"));
        assertThat(lines).isEmpty();
        completeStep().handle(new CompleteStepManuallyCommand("p", "se-c", List.of(), "x", "bob"));
        completeStep().handle(new CompleteStepManuallyCommand("p", "nope", List.of(), "x", "bob"));
        assertThat(lines).hasSize(2).allMatch(line -> line.contains("NOT carried out"));
        verify(processes, never()).save(any());
    }

    // ── Retry from step ──────────────────────────────────────────────────────────────────────────

    RetryFromStepUseCase retryFrom() {
        return new RetryFromStepUseCase(processes, steps, processLock, downstream, stepOver, locks, metrics, rules,
                audit, cancel);
    }

    @Test
    void retryFromResetsTheStepAndEverythingDownstream() {
        process(ProcessStatus.ERROR);
        var start = se("start", "START", Map.of(), StepExecutionStatus.COMPLETED);
        var a = se("a", "ACTION", Map.of("preconditionStepId", "start"), StepExecutionStatus.COMPLETED);
        var b = se("b", "ACTION", Map.of("preconditionStepId", "a"), StepExecutionStatus.ERROR);
        stepsAre(start, a, b);

        retryFrom().handle(new RetryFromStepCommand("p", "a", "fixed", "carol"));

        assertThat(start.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(a.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(b.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(a.getDispatchCount()).isPositive();
        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(saved.getValue().getCompletionPercentage()).isEqualTo(33);
        verify(metrics).retryPerformed("w", WorkflowMetrics.RetryTrigger.MANUAL);
        verify(stepOver).handle(any());
        assertThat(lines).singleElement().asString().contains("reopened a (was COMPLETED), b (was ERROR)");
    }

    @Test
    void insideACriticalSectionTheLockIsTakenAgainOrTheRetryMovesBack() {
        process(ProcessStatus.ERROR, new Variable("k", "K1"));
        var lockStep = se("lock", "LOCK", Map.of("lockName", "n", "lockKey", "k"), StepExecutionStatus.COMPLETED);
        var work = se("work", "ACTION", Map.of("preconditionStepId", "lock"), StepExecutionStatus.ERROR);
        var unlock = se("unlock", "UNLOCK", Map.of("lockName", "n", "lockKey", "k", "preconditionStepId", "work"),
                StepExecutionStatus.CREATED);
        stepsAre(lockStep, work, unlock);
        when(locks.tryAcquire("n", "K1", "p")).thenReturn(LockService.Outcome.ACQUIRED);

        retryFrom().handle(new RetryFromStepCommand("p", "work", "r", "carol"));

        assertThat(lockStep.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(lines.getLast()).contains("re-took lock(s) n/K1");

        // Busy now: the LOCK step is reopened too.
        work.updateStatus(StepExecutionStatus.ERROR);
        when(locks.tryAcquire("n", "K1", "p")).thenReturn(LockService.Outcome.BUSY);
        retryFrom().handle(new RetryFromStepCommand("p", "work", "r", "carol"));
        assertThat(lockStep.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(lines.getLast()).contains("moved back to LOCK step(s) [lock]");
    }

    @Test
    void retryFromRefusalsAreRecordedAndAPausedProcessStaysPaused() {
        process(ProcessStatus.PAUSED);
        var a = se("a", "ACTION", Map.of(), StepExecutionStatus.RUNNING);
        var b = se("b", "ACTION", Map.of("preconditionStepId", "a"), StepExecutionStatus.CREATED);
        stepsAre(a, b);

        retryFrom().handle(new RetryFromStepCommand("p", "b", "r", "carol"));
        assertThat(lines.getLast()).contains("NOT carried out", "has not run yet");

        retryFrom().handle(new RetryFromStepCommand("p", "a", "r", "carol"));
        verify(downstream).publish(eq(new TaskCancellationRequested("se-a")), isNull());
        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        verify(stepOver, never()).handle(any());
    }
}
