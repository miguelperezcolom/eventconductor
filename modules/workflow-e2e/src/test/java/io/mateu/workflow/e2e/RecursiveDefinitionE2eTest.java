package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HARD-DEF-13 — a definition that starts itself back up, the long way round.
 *
 * <p>{@code checkInvariants} refuses a PROCESS step naming its own workflow as the child, and that
 * is the only spelling it can refuse: a definition is validated alone, so it cannot see that the
 * workflow it starts starts this one back. Two files, each of them individually valid, are enough —
 * A starts B, B starts A — and every generation started the next for as long as the store would
 * take rows. Nothing in the definition is wrong; the recursion only exists between the two.
 *
 * <p>So the limit is at runtime, on how deep processes may nest, and it is asserted from outside:
 * the fan-out stops, the step that asked for the child over the line fails through the ordinary
 * failure pipeline rather than waiting for a child that will never arrive, and — the part that
 * makes this a containment test rather than a counting test — the engine keeps running afterwards.
 */
class RecursiveDefinitionE2eTest extends AbstractE2eTest {

    /** Read from the environment, so the assertion is against the limit actually in force. */
    @Value("${workflow.max-process-depth:20}")
    int maxDepth;

    @Test
    void mutuallyRecursiveDefinitionsStopAtTheDepthLimitInsteadOfSpawningForEver() {
        long before = processRepository.findAll().size();

        createProcess("recursion-a", "recursion-root");

        long spawned = processRepository.findAll().size() - before;

        // Exactly the limit: the root process is the first level, so the limit counts it. Asserted
        // tightly on purpose — "not unbounded" is unfalsifiable in a test that has to finish,
        // whereas this number moves with workflow.max-process-depth, so only the limit can produce
        // it. Verified by running the suite at a different limit and watching the number follow.
        assertThat(spawned)
                .as("a mutually recursive pair must nest exactly as deep as the limit allows, and stop")
                .isEqualTo(maxDepth);
    }

    @Test
    void theStepThatAskedForTheChildOverTheLineFailsRatherThanWaitingForEver() {
        createProcess("recursion-a", "recursion-deep");

        // Somewhere down the chain a PROCESS step asked for a child that was refused. That step
        // must be ERROR: the alternative is a step left PENDING for a child that never exists,
        // which is a process wedged in RUNNING with nothing left to move it.
        var refused = stepExecutionRepository.findAll().stream()
                .filter(execution -> StepExecutionStatus.ERROR.equals(execution.getStatus()))
                .filter(execution -> execution.getStepId().startsWith("spawn-"))
                .toList();

        assertThat(refused)
                .as("the child over the depth limit must fail its PROCESS step, not hang it")
                .isNotEmpty();
        assertThat(process("recursion-deep").getStatus())
                .as("and the failure must propagate up to the root process")
                .isEqualTo(ProcessStatus.ERROR);
    }

    /** Containment: the engine that just refused a runaway definition still runs ordinary work. */
    @Test
    void theEngineKeepsWorkingAfterRefusingARunawayDefinition() {
        createProcess("recursion-a", "recursion-noisy");

        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        createProcess("sequential-3", "after-the-runaway");

        assertThat(process("after-the-runaway").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
