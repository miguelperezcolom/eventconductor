package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Engine observability metrics, active only when Micrometer is on the classpath
 * AND the host application provides a {@code MeterRegistry} bean (typically via
 * Spring Boot Actuator). Otherwise {@link WorkflowEngineAutoConfiguration} falls
 * back to the no-op {@link WorkflowMetrics}.
 */
@AutoConfiguration(
        before = WorkflowEngineAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class WorkflowMetricsAutoConfiguration {

    /**
     * How long a count gauge may reuse its last value. {@code PT0S} restores a query per scrape.
     *
     * <p>Read off the {@link org.springframework.core.env.Environment} and parsed here rather than
     * injected with {@code @Value}: a placeholder on a {@code @Bean} parameter needs a
     * {@code PropertySourcesPlaceholderConfigurer} in the context, which a real application has and
     * a sliced test context does not — so the convenience would have made this auto-configuration
     * fail to load in exactly the harness that tests it. {@code DurationStyle} is what Boot's own
     * binder uses, so {@code 30s}, {@code PT30S} and {@code 2m} all work the same as anywhere else.
     */
    private static Duration gaugeTtl(org.springframework.core.env.Environment environment) {
        var configured = environment.getProperty("workflow.metrics.gauge-ttl");
        return configured == null || configured.isBlank()
                ? Duration.ofSeconds(30)
                : org.springframework.boot.convert.DurationStyle.detectAndParse(configured);
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowMetrics.class)
    MicrometerWorkflowMetrics micrometerWorkflowMetrics(MeterRegistry meterRegistry) {
        return new MicrometerWorkflowMetrics(meterRegistry);
    }

    // Registered after all singletons exist so it does not depend on bean ordering:
    // the ProcessRepository implementation varies per persistence mode and may be
    // absent in applications that only embed the forms engine.
    @Bean
    SmartInitializingSingleton eventconductorRunningProcessesGauge(
            MeterRegistry meterRegistry, ObjectProvider<ProcessRepository> processRepositories,
            org.springframework.core.env.Environment environment) {
        var gaugeTtl = gaugeTtl(environment);
        return () -> processRepositories.ifAvailable(repository -> {
            var cached = new CachedCount(() -> repository.countByStatus(ProcessStatus.RUNNING), gaugeTtl);
            Gauge.builder(MicrometerWorkflowMetrics.PROCESSES_RUNNING, cached, CachedCount::value)
                    .description("Workflow processes currently in RUNNING status. Sampled at most "
                            + "once per workflow.metrics.gauge-ttl, because counting them costs one "
                            + "index entry per running process on the engine's own database")
                    // The gauge holds its source weakly, and this one is referenced by nothing else.
                    .strongReference(true)
                    .register(meterRegistry);
        });
    }

    /**
     * The pending-outbox gauge only makes sense with JPA persistence (in memory mode
     * events are dispatched synchronously and there is no outbox). Guarded on the
     * classpath because spring-data-jpa is an optional dependency of this module.
     *
     * The MeterRegistry guard is repeated here on purpose: applications that
     * component-scan io.mateu register this nested class independently of the
     * enclosing class's conditions, so without its own guard the gauge bean
     * would fail to start apps that have no MeterRegistry.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
    @ConditionalOnBean(MeterRegistry.class)
    static class OutboxMetricsConfiguration {

        @Bean
        SmartInitializingSingleton eventconductorPendingOutboxGauge(
                MeterRegistry meterRegistry, ObjectProvider<OutboxMessageEntityRepository> outboxRepositories,
                org.springframework.core.env.Environment environment) {
            var gaugeTtl = gaugeTtl(environment);
            return () -> outboxRepositories.ifAvailable(repository -> {
                var cached = new CachedCount(
                        () -> repository.countByStatus(OutboxMessageStatus.Pending.name()), gaugeTtl);
                Gauge.builder(MicrometerWorkflowMetrics.OUTBOX_PENDING, cached, CachedCount::value)
                        .description("Outbox messages waiting to be relayed. Sampled at most once per "
                                + "workflow.metrics.gauge-ttl: this count is cheapest when the outbox is "
                                + "drained and dearest when it is backed up, which is when the database "
                                + "can least afford to answer it once per pod per scrape")
                        .strongReference(true)
                        .register(meterRegistry);
            });
        }
    }
}
