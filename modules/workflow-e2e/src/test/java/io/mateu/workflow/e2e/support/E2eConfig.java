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
    public TestWorker testWorker(UpdateStepExecutionUseCase updateStepExecution,
                                 org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.infra.out.events.PublishedEventDelivery> delivery) {
        // Lazily: the engine's delivery reaches the engine's doors, which reach this worker.
        return new TestWorker(updateStepExecution, (event, trace) -> delivery.getObject().deliver(event));
    }

    /**
     * The published agents AGENT steps are validated against at import — never a real catalogue in a test.
     * {@code claims-assessor} may use the {@code crm} MCP server and nothing else.
     */
    @Bean
    public io.mateu.workflow.application.out.WorkflowAgentCatalogue workflowAgentCatalogue() {
        var agents = java.util.List.of(new io.mateu.workflow.application.out.WorkflowAgentCatalogue.Agent(
                "claims-assessor", "Claims assessor", "Assesses an insurance claim", java.util.List.of("crm"),
                java.util.List.of(), java.util.List.of(), java.util.Map.of("type", "object"), "rev-1"));
        return () -> agents;
    }

    @Bean
    public PublishedEvents publishedEvents() {
        return new PublishedEvents();
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
