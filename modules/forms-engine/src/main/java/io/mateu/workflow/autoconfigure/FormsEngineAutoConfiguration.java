package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.FormsMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FormsEngineAutoConfiguration {

    // Fallback when Micrometer is absent or no MeterRegistry bean exists —
    // FormsMetricsAutoConfiguration runs before this and wins when active.
    @Bean
    @ConditionalOnMissingBean(FormsMetrics.class)
    FormsMetrics formsMetrics() {
        return FormsMetrics.NOOP;
    }
}
