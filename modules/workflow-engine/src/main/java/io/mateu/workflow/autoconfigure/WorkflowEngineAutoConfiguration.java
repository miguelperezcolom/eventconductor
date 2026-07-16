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
}
