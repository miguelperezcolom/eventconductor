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

    // Same shape as the metrics fallback, and gated the same way: only when Micrometer tracing is
    // not on the classpath, so it never races WorkflowTracingAutoConfiguration's real implementation
    // under component scan. With the tracing classes present, MicrometerWorkflowTracing resolves its
    // tracer lazily and runs untraced until one exists (or forever, if no bridge is configured), so
    // this no-op is only needed when the engine carries no tracing dependency at all.
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(io.mateu.workflow.application.out.WorkflowTracing.class)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing() {
        return io.mateu.workflow.application.out.WorkflowTracing.NOOP;
    }
}
