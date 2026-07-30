package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Rule-catalog observability metrics, active only when Micrometer is on the classpath
 * AND the host application provides a {@code MeterRegistry} bean (typically via
 * Spring Boot Actuator). Otherwise {@link RulesEngineAutoConfiguration} falls
 * back to the no-op {@link RuleCatalogMetrics}.
 */
@AutoConfiguration(
        before = RulesEngineAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class RuleCatalogMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RuleCatalogMetrics.class)
    MicrometerRuleCatalogMetrics micrometerRuleCatalogMetrics(MeterRegistry meterRegistry) {
        return new MicrometerRuleCatalogMetrics(meterRegistry);
    }
}
