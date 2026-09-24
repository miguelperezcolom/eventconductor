package io.mateu.workflow.application.sync;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.services.CompensationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineReplyPolicyTest {

    StepExecutionRepository steps = mock(StepExecutionRepository.class);
    LogMessageRepository logs = mock(LogMessageRepository.class);
    CompensationService compensation = mock(CompensationService.class);
    EngineReplyPolicy policy = new EngineReplyPolicy(steps, logs, compensation);

    private static Step step(String id) {
        return new Step(id, "wd", StepType.ACTION, "Step " + id, null, "start", null, null, false, null, null, null,
                null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static Process process(ProcessStatus status, SyncInvocation sync) {
        var definition = new WorkflowDefinition("wd", "WD", 1, null, false, 0, false, null, 0, List.of())
                .withSyncInvocation(sync);
        return Process.builder().id("p").status(status).variables(List.of())
                .workflowDefinitionJson(JsonSerializer.toJson(definition)).build();
    }

    private void decision(CompensationService.Outcome outcome) {
        when(compensation.decide(any())).thenReturn(new CompensationService.Decision(outcome, null));
    }

    @Test
    void aDefinitionThatIsNotSyncInvocableIsLeftAlone() {
        var process = process(ProcessStatus.ERROR, null);
        policy.apply(process);
        assertThat(process.hasReplied()).isFalse();
    }

    @Test
    void aRunningProcessIsLeftAlone() {
        var process = process(ProcessStatus.RUNNING, new SyncInvocation(true, null, null, 0));
        policy.apply(process);
        assertThat(process.hasReplied()).isFalse();
    }

    @Test
    void immediateFailureCarriesTheRollbackStateAndTheFailedStep() {
        decision(CompensationService.Outcome.RUN);
        var failed = StepExecution.builder().id("se-pay").stepId("pay").processId("p")
                .stepJson(JsonSerializer.toJson(step("pay"))).status(StepExecutionStatus.ERROR)
                .finishedAt(LocalDateTime.now()).variables(List.of()).build();
        when(steps.findByProcessId("p")).thenReturn(List.of(failed));
        when(logs.findByProcessId("p")).thenReturn(List.of(LogMessage.builder().id("l").processId("p")
                .stepExecutionId("se-pay").messageType("Error").message("card declined")
                .timestamp(LocalDateTime.now()).build()));

        var process = process(ProcessStatus.ERROR, new SyncInvocation(true, null, null, 0));
        policy.apply(process);

        assertThat(process.getReply().outcome()).isEqualTo(ProcessReply.Outcome.FAILED);
        assertThat(process.getReply().compensation()).isEqualTo(ProcessReply.Compensation.IN_PROGRESS);
        assertThat(process.getReply().error()).isEqualTo("Step 'pay' (Step pay) failed: card declined");
    }

    @Test
    void afterCompensationWaitsForTheRollbackUnlessThereIsNothingToUndo() {
        var after = new SyncInvocation(true, SyncInvocation.FailurePolicy.REPLY_AFTER_COMPENSATION, null, 0);
        for (var outcome : List.of(CompensationService.Outcome.RUN, CompensationService.Outcome.WAITING,
                CompensationService.Outcome.DONE, CompensationService.Outcome.FAILED)) {
            decision(outcome);
            var process = process(ProcessStatus.ERROR, after);
            policy.apply(process);
            assertThat(process.hasReplied()).as(outcome.name()).isFalse();
        }
        decision(CompensationService.Outcome.NONE);
        var process = process(ProcessStatus.ERROR, after);
        policy.apply(process);
        assertThat(process.getReply().compensation()).isEqualTo(ProcessReply.Compensation.NONE);
    }

    @Test
    void theEndsOfARollbackAndOtherEndsAnswerForTheProcess() {
        var sync = new SyncInvocation(true, null, null, 0);
        var cases = List.of(
                List.of(ProcessStatus.COMPENSATED, ProcessReply.Outcome.COMPENSATED),
                List.of(ProcessStatus.COMPENSATION_FAILED, ProcessReply.Outcome.COMPENSATION_FAILED),
                List.of(ProcessStatus.CANCELLED, ProcessReply.Outcome.CANCELLED),
                List.of(ProcessStatus.COMPLETED, ProcessReply.Outcome.COMPLETED_WITHOUT_REPLY));
        for (var c : cases) {
            var process = process((ProcessStatus) c.get(0), sync);
            policy.apply(process);
            assertThat(process.getReply().outcome()).as(c.get(0).toString()).isEqualTo(c.get(1));
        }
    }

    @Test
    void aProcessThatAlreadyRepliedKeepsItsAnswer() {
        var process = process(ProcessStatus.COMPLETED, new SyncInvocation(true, null, null, 0));
        process.recordReply(ProcessReply.replied("reply", "{}"));
        policy.apply(process);
        assertThat(process.getReply().outcome()).isEqualTo(ProcessReply.Outcome.REPLIED);
    }
}
