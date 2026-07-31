package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PROCESS steps (child workflows): the parent's "spawn-child" step starts a process of
 * "child-flow" under the deterministic businessKey {@code parent:<stepExecutionId>} and
 * waits; when the child completes, only the variables named in the step's
 * {@code outputVariables} flow back into the parent.
 */
class ChildProcessE2eTest extends AbstractE2eTest {

    @Test
    void childProcessCompletionCompletesTheParent() {
        // The child writes two variables; the parent's PROCESS step only lists "result"
        // in outputVariables, so "secret" must stay in the child.
        worker.on("child-work", TestWorker.succeed(var("result", "42"), var("secret", "child-only")));

        createProcess("parent-flow", "parent-1", new io.mateu.workflow.dtos.Variable("orderId", "o-1"));

        // The child ran under its deterministic businessKey and completed.
        var spawnStep = step("parent-1", "spawn-child");
        var child = process("parent:" + spawnStep.getId());
        assertThat(child.getWorkflowDefinitionId()).isEqualTo("child-flow");
        assertThat(child.getParentStepExecutionId()).isEqualTo(spawnStep.getId());
        assertThat(child.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        // The child started from a snapshot of the parent's variables.
        assertThat(child.getVariables()).anyMatch(v -> "orderId".equals(v.name()) && "o-1".equals(v.value()));

        // The parent step completed and the parent process ran to its END.
        assertThat(step("parent-1", "spawn-child").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("parent-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        // outputVariables filtering: "result" came back, "secret" did not.
        assertThat(process("parent-1").getVariables())
                .anyMatch(v -> "result".equals(v.name()) && "42".equals(v.value()));
        assertThat(process("parent-1").getVariables())
                .noneMatch(v -> "secret".equals(v.name()));
    }

    @Test
    void failingChildProcessFailsTheParentStepAndProcess() {
        worker.on("child-work", TestWorker.fail());

        createProcess("parent-flow", "parent-2");

        var spawnStep = step("parent-2", "spawn-child");
        assertThat(process("parent:" + spawnStep.getId()).getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(spawnStep.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("parent-2").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void redeliveredProcessStepStartDoesNotSpawnASecondChild() {
        // The deterministic businessKey "parent:<stepExecutionId>" makes child creation
        // idempotent: replaying the creation request must dedupe by businessKey.
        worker.on("child-work", TestWorker.succeed(var("result", "42")));

        createProcess("parent-flow", "parent-3");

        var spawnStep = step("parent-3", "spawn-child");
        processUpstreamEventUseCase.handle(new io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand(
                new io.mateu.workflow.dtos.events.integration.ProcessCreationRequested(
                        "child-flow", "parent:" + spawnStep.getId(), java.util.List.of(), spawnStep.getId())));

        long children = processRepository.findAll().stream()
                .filter(p -> ("parent:" + spawnStep.getId()).equals(p.getBusinessKey()))
                .count();
        assertThat(children).isEqualTo(1);
        assertThat(process("parent-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void cancellingTheParentCancelsTheInFlightChild() {
        // The child's worker never responds, so the child stays alive until cancelled.
        worker.on("child-work", TestWorker.deferForever());

        createProcess("parent-flow", "parent-4");

        var spawnStep = step("parent-4", "spawn-child");
        var childKey = "parent:" + spawnStep.getId();
        assertThat(process(childKey).getStatus()).isEqualTo(ProcessStatus.RUNNING);

        cancelProcessUseCase.handle(new CancelProcessCommand(process("parent-4").getId()));

        // The parent's cancellation cascaded into the child: the child process AND its
        // in-flight steps all ended CANCELLED.
        assertThat(process("parent-4").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(step("parent-4", "spawn-child").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process(childKey).getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(step(childKey, "child-work").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(steps(childKey))
                .allMatch(se -> se.getStatus() == StepExecutionStatus.CANCELLED
                        || se.getStatus() == StepExecutionStatus.COMPLETED);
    }

    @Test
    void parentProcessStepTimeoutCancelsTheInFlightChild() {
        // The child hangs; the parent's PROCESS step carries a 500ms timeout and no
        // retries, so the timeout scheduler finally fails it — and the child must not be
        // left running for a parent that will never consume its result.
        worker.on("child-work", TestWorker.deferForever());

        createProcess("parent-timeout", "pt-1");

        var spawnStep = step("pt-1", "spawn-child");
        var childKey = "parent:" + spawnStep.getId();
        assertThat(process(childKey).getStatus()).isEqualTo(ProcessStatus.RUNNING);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("pt-1", "spawn-child").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
            assertThat(process("pt-1").getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(process(childKey).getStatus()).isEqualTo(ProcessStatus.CANCELLED);
            assertThat(step(childKey, "child-work").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        });
    }
}
