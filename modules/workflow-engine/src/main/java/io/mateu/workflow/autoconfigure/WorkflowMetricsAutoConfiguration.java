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
            MeterRegistry meterRegistry, ObjectProvider<ProcessRepository> processRepositories) {
        return () -> processRepositories.ifAvailable(repository ->
                Gauge.builder(MicrometerWorkflowMetrics.PROCESSES_RUNNING, repository,
                                r -> r.countByStatus(ProcessStatus.RUNNING))
                        .description("Workflow processes currently in RUNNING status")
                        .register(meterRegistry));
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
                MeterRegistry meterRegistry, ObjectProvider<OutboxMessageEntityRepository> outboxRepositories) {
            return () -> outboxRepositories.ifAvailable(repository ->
                    Gauge.builder(MicrometerWorkflowMetrics.OUTBOX_PENDING, repository,
                                    r -> r.countByStatus(OutboxMessageStatus.Pending.name()))
                            .description("Outbox messages waiting to be relayed")
                            .register(meterRegistry));
        }
    }
}
