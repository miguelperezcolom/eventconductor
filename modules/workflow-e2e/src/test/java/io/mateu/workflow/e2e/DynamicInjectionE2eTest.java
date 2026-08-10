package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-DYN. Runtime step injection driven by a DYNAMIC step's worker reply.
 *
 * <p>The {@code dynamic-inject} definition is just {@code start -> plan(DYNAMIC)}. The worker for
 * {@code plan} does what a real DYNAMIC worker would: it emits a {@link StepsInjected} carrying a
 * batch of new steps and then reports COMPLETED. The batch fans two ACTIONs into a JOIN, all
 * preconditioned so they are reachable, and the process runs the injected steps to completion.
 *
 * <p>Injection is fed the same way every other e2e feeds the engine: a domain event through the
 * public upstream surface ({@code ProcessUpstreamEventUseCase}), exactly what the Kafka consumer
 * or the embedded loop would deliver. In embedded + memory mode execution is synchronous, so the
 * whole flow resolves within the {@code createProcess} call that dispatches {@code plan}.
 */
class DynamicInjectionE2eTest extends AbstractE2eTest {

    /**
     * A DYNAMIC worker: inject {@code stepsJson} for this step's process, then report COMPLETED —
     * the same reply order a real worker uses (inject, then finish the injecting step).
     */
    private TestWorker.Behavior injectThenComplete(String stepsJson) {
        return (req, cb, invocation) -> {
            processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                    new StepsInjected(req.taskExecutionId(), req.processId(), stepsJson)));
            cb.complete();
        };
    }

    @Test
    void aDynamicStepInjectsStepsThatFanIntoAJoinAndTheProcessCompletes() {
        // Two tasks that fan into a JOIN, all reachable from the DYNAMIC step 'plan'.
        var batch = """
                [
                  {"id":"task-a","type":"ACTION","name":"Task A","topic":"work","preconditionStepId":"plan"},
                  {"id":"task-b","type":"ACTION","name":"Task B","topic":"work","preconditionStepId":"plan"},
                  {"id":"merge","type":"JOIN","name":"Merge","preconditionStepIds":["task-a","task-b"]}
                ]
                """;
        worker.on("plan", injectThenComplete(batch));
        worker.on("task-a", TestWorker.succeed());
        worker.on("task-b", TestWorker.succeed());

        createProcess("dynamic-inject", "dyn-1");

        // The injecting step completed…
        assertThat(step("dyn-1", "plan").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // …the injected steps actually ran…
        assertThat(worker.invocationsOf("task-a")).isEqualTo(1);
        assertThat(worker.invocationsOf("task-b")).isEqualTo(1);
        assertThat(step("dyn-1", "task-a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("dyn-1", "task-b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("dyn-1", "merge").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // …each is marked with the DYNAMIC step execution that injected it (exact provenance)…
        var plan = step("dyn-1", "plan");
        assertThat(step("dyn-1", "task-a").getInjectedByStepExecutionId()).isEqualTo(plan.id());
        assertThat(step("dyn-1", "merge").getInjectedByStepExecutionId()).isEqualTo(plan.id());
        // …and the process reached a clean terminal COMPLETED (implicit completion, no END declared).
        assertThat(process("dyn-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aRedeliveredInjectionDoesNotDoubleInject() {
        var batch = """
                [
                  {"id":"task-a","type":"ACTION","name":"Task A","topic":"work","preconditionStepId":"plan"}
                ]
                """;
        // Inject twice from the same DYNAMIC step, then complete — the second injection is a
        // redelivery and must be a no-op (exact idempotency on the injecting step's id).
        worker.on("plan", (req, cb, invocation) -> {
            var event = new StepsInjected(req.taskExecutionId(), req.processId(), batch);
            processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
            processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
            cb.complete();
        });
        worker.on("task-a", TestWorker.succeed());

        createProcess("dynamic-inject", "dyn-2");

        // task-a exists exactly once and ran exactly once — the redelivery injected nothing.
        assertThat(steps("dyn-2").stream().filter(s -> "task-a".equals(s.getStepId())).count()).isEqualTo(1);
        assertThat(worker.invocationsOf("task-a")).isEqualTo(1);
        assertThat(process("dyn-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aCompensableInjectedStepIsRolledBackWhenAnInjectedSuccessorFails() {
        // The DYNAMIC step injects a compensable step, a successor that fails, and the compensation.
        // provision succeeds and boom fails, so the saga rolls back the step that committed work —
        // provision, via deprovision — proving an injected step participates in compensation like
        // any definition-declared one.
        var batch = """
                [
                  {"id":"provision","type":"ACTION","name":"Provision","topic":"work",
                   "compensable":true,"compensationStepId":"deprovision","preconditionStepId":"plan"},
                  {"id":"boom","type":"ACTION","name":"Boom","topic":"work","retries":0,
                   "preconditionStepId":"provision"},
                  {"id":"deprovision","type":"ACTION","name":"Deprovision","topic":"work"}
                ]
                """;
        worker.on("plan", injectThenComplete(batch));
        worker.on("provision", TestWorker.succeed());
        worker.on("boom", TestWorker.fail());          // retries=0 → fails immediately, triggers rollback
        worker.on("deprovision", TestWorker.succeed()); // provision's compensation

        createProcess("dynamic-inject-compensation", "dyn-comp-1");

        assertThat(step("dyn-comp-1", "provision").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("dyn-comp-1", "boom").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        // The injected compensable step is compensated: its compensation ran…
        assertThat(worker.invocationsOf("deprovision"))
                .as("an injected compensable step must be rolled back like any other")
                .isEqualTo(1);
        assertThat(step("dyn-comp-1", "deprovision").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // …and the saga rolled back cleanly to COMPENSATED, not left in ERROR.
        assertThat(process("dyn-comp-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }
}
