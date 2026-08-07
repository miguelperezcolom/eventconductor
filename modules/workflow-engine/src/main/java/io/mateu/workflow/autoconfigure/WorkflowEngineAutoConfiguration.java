package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class WorkflowEngineAutoConfiguration {

    // Fallback for when Micrometer is not even on the classpath. Gated on the *absence* of
    // MeterRegistry rather than left to lose an ordering race: an application that scans this
    // package (its @SpringBootApplication sits at io.mateu.workflow) picks both auto-configurations
    // up as plain @Configuration, where @AutoConfiguration(before=...) does not apply and the two
    // WorkflowMetrics beans are resolved in class-name order — "Engine" before "Metrics" — so this
    // no-op would register first and @ConditionalOnMissingBean on the Micrometer one would back it
    // off, silently. With Micrometer present, MicrometerWorkflowMetrics resolves its registry
    // lazily (dodging a different creation-order race), so it is safe to hand it the field
    // unconditionally here and let it degrade to a no-op until the registry exists.
    @Bean
    @ConditionalOnMissingBean(WorkflowMetrics.class)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    WorkflowMetrics workflowMetrics() {
        return WorkflowMetrics.NOOP;
    }

    // Same shape as the metrics fallback: WorkflowTracingAutoConfiguration runs before this and
    // wins when a Tracer is available; with no tracing on the classpath the engine describes
    // nothing and costs nothing.
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(io.mateu.workflow.application.out.WorkflowTracing.class)
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing() {
        return io.mateu.workflow.application.out.WorkflowTracing.NOOP;
    }
}
