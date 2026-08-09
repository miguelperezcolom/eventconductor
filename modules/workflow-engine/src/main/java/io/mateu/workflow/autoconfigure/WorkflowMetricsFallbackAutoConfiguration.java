package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Guarantees that a {@link WorkflowMetrics} bean exists, whatever else is or is not on the
 * classpath and whatever the host application has excluded.
 *
 * <p>It is not decoration: {@code WorkflowMetrics} is a constructor dependency of the engine's own
 * beans — {@code PartitionOwnedProcessLockService}, {@code StepOverProcessUseCase} and others — so
 * its absence is not "no metrics", it is a context that cannot start, reported as an
 * {@code UnsatisfiedDependencyException} several beans away from the cause.
 *
 * <p>The no-op used to live in {@link WorkflowEngineAutoConfiguration} gated on
 * {@code @ConditionalOnMissingClass(MeterRegistry)}, which covered "Micrometer is absent" and left
 * a second case open: Micrometer <em>present</em> and {@link WorkflowMetricsAutoConfiguration}
 * excluded, by
 * <pre>spring.autoconfigure.exclude=io.mateu.workflow.autoconfigure.WorkflowMetricsAutoConfiguration</pre>
 * which is a supported thing for a host to do and the engine's own distributed test suite did. Then
 * neither definition applies and nothing provides the bean. That is how the whole suite spent days
 * failing to boot a single context.
 *
 * <h2>Why a class of its own, rather than dropping the class condition where it was</h2>
 *
 * <p>Because the guard was load-bearing for a different reason. An application whose
 * {@code @SpringBootApplication} sits at {@code io.mateu.workflow} component-scans this package, and
 * then both configurations are plain {@code @Configuration}: {@code @AutoConfiguration} ordering is
 * not honoured, the beans resolve in class-name order, and {@code WorkflowEngine...} sorts before
 * {@code WorkflowMetrics...} — so an unguarded no-op there would register first and
 * {@code @ConditionalOnMissingBean} would silently back the Micrometer implementation off. That is
 * the regression that made every engine metric disappear once already.
 *
 * <p>A class named {@code WorkflowMetricsFallback...} sorts <em>after</em>
 * {@code WorkflowMetricsAutoConfiguration} under that same class-name ordering, and
 * {@code @AutoConfiguration(after = ...)} pins it in the ordinary auto-configuration path. The real
 * implementation wins whenever it is present, by both routes, and this one only fills a hole.
 */
@AutoConfiguration(after = WorkflowMetricsAutoConfiguration.class)
public class WorkflowMetricsFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowMetrics.class)
    WorkflowMetrics workflowMetrics() {
        return WorkflowMetrics.NOOP;
    }
}
