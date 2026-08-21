package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.RecordingTracing;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRACE-20..21 — the configured sampling probability governs process traces, end to end.
 *
 * <p>Its own context, at a probability of zero, because that is the only way to see the decision
 * take effect: the rest of the suite runs at 1.0 so that its assertions are about the shape of a
 * trace rather than about whether a given process fell inside the sampled fraction.
 *
 * <p>What used to happen is worth stating, because nothing about it looked wrong. Boot's sampler is
 * {@code ParentBased(TraceIdRatioBased(p))} — it honours a remote parent's decision and only
 * consults the ratio when there is no parent — and the derived anchor always claimed to be sampled.
 * So every process trace was exported whatever the property said, while the property went on
 * governing the auto-instrumented HTTP and JDBC traces exactly as documented.
 */
@TestPropertySource(properties = "management.tracing.sampling.probability=0.0")
class ProcessTraceSamplingE2eTest extends AbstractE2eTest {

    @Autowired RecordingTracing tracing;

    @BeforeEach
    void forgetEarlierSpans() {
        tracing.clear();
    }

    /** TRACE-20. Nothing is emitted for a process the sampler decided against — not even a fragment. */
    @Test
    void aProcessTheSamplerDeclinedEmitsNothingAtAll() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "unsampled");

        assertThat(tracing.spans())
                .as("at a probability of zero the engine must emit no span for this process — "
                        + "neither the process and step spans nor the live dispatch ones")
                .isEmpty();
    }

    /**
     * TRACE-21. And the process runs exactly as it would have. Tracing describes the work; a
     * decision not to describe it must not change it.
     */
    @Test
    void anUnsampledProcessRunsExactlyAsASampledOneWould() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "unsampled-still-runs");

        assertThat(process("unsampled-still-runs").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("s3")).isEqualTo(1);
    }
}
