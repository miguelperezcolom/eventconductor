package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the programmable {@link TestWorker} as the single embedded task executor,
 * and a real {@link MeterRegistry} so the engine's metrics wiring is exercised
 * (and the outbox/running-process gauges resolve deterministically).
 */
@TestConfiguration
public class E2eConfig {

    @Bean
    public TestWorker testWorker(UpdateStepExecutionUseCase updateStepExecution) {
        return new TestWorker(updateStepExecution);
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * Wins over the engine's no-op fallback, which is {@code @ConditionalOnMissingBean}. Every e2e
     * test therefore runs with the tracing port exercised rather than stubbed out, and
     * {@code ProcessTraceE2eTest} can read back the trace the engine emitted.
     */
    @Bean
    public RecordingTracing workflowTracing() {
        return new RecordingTracing();
    }
}
