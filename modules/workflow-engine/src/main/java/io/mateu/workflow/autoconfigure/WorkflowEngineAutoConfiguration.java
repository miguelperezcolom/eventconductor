package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class WorkflowEngineAutoConfiguration {

    // Fallback when Micrometer is absent or no MeterRegistry bean exists —
    // WorkflowMetricsAutoConfiguration runs before this and wins when active.
    @Bean
    @ConditionalOnMissingBean(WorkflowMetrics.class)
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
