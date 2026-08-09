package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowMetrics} and {@link WorkflowTracing} are constructor dependencies of engine beans,
 * so "no bean" is not "no observability" — it is a context that will not start, reported as an
 * {@code UnsatisfiedDependencyException} several beans from the cause.
 *
 * <p>These tests pin the case that was open and cost the distributed suite days of red: Micrometer
 * on the classpath <em>and</em> the real auto-configuration excluded. The old no-op was gated on the
 * Micrometer class being absent, so it did not apply, and nothing else did either.
 */
class CrossCuttingPortsAlwaysHaveABeanTest {

    /**
     * Excluding an auto-configuration is modelled by not registering it: {@code
     * spring.autoconfigure.exclude} is applied by {@code AutoConfigurationImportSelector}, which is
     * not in play when a runner is handed its configurations directly — so setting the property
     * here would assert nothing. What reaches the engine either way is a context in which the real
     * auto-configuration never contributed, which is exactly what these runners build. The property
     * itself is exercised end to end by the distributed suite, which sets it against a real
     * {@code SpringApplication}.
     */
    private final ApplicationContextRunner everything = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsAutoConfiguration.class,
                    WorkflowMetricsFallbackAutoConfiguration.class,
                    WorkflowTracingAutoConfiguration.class,
                    WorkflowTracingFallbackAutoConfiguration.class));

    private final ApplicationContextRunner metricsExcluded = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsFallbackAutoConfiguration.class,
                    WorkflowTracingAutoConfiguration.class,
                    WorkflowTracingFallbackAutoConfiguration.class));

    private final ApplicationContextRunner tracingExcluded = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsAutoConfiguration.class,
                    WorkflowMetricsFallbackAutoConfiguration.class,
                    WorkflowTracingFallbackAutoConfiguration.class));

    private final ApplicationContextRunner bothExcluded = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsFallbackAutoConfiguration.class,
                    WorkflowTracingFallbackAutoConfiguration.class));

    @Test
    void metricsSurviveTheirAutoConfigurationBeingExcludedWithMicrometerPresent() {
        metricsExcluded.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowMetrics.class);
                    assertThat(context.getBean(WorkflowMetrics.class)).isSameAs(WorkflowMetrics.NOOP);
                });
    }

    @Test
    void tracingSurvivesItsAutoConfigurationBeingExcludedWithMicrometerPresent() {
        tracingExcluded.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowTracing.class);
                    assertThat(context.getBean(WorkflowTracing.class)).isSameAs(WorkflowTracing.NOOP);
                });
    }

    @Test
    void bothSurviveBothBeingExcluded() {
        bothExcluded.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowMetrics.class);
                    assertThat(context).hasSingleBean(WorkflowTracing.class);
                });
    }

    @Test
    void theRealImplementationStillWinsWhenNothingIsExcluded() {
        // The fallback must never shadow the real one — the regression that made every engine
        // metric vanish once already.
        everything.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    assertThat(context.getBean(WorkflowMetrics.class))
                            .isInstanceOf(MicrometerWorkflowMetrics.class);
                    assertThat(context.getBean(WorkflowTracing.class))
                            .isInstanceOf(MicrometerWorkflowTracing.class);
                });
    }

    @Test
    void withoutMicrometerOnTheClasspathTheFallbacksProvideTheBeans() {
        everything.withClassLoader(new FilteredClassLoader(MeterRegistry.class, Tracer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WorkflowMetrics.class)).isSameAs(WorkflowMetrics.NOOP);
                    assertThat(context.getBean(WorkflowTracing.class)).isSameAs(WorkflowTracing.NOOP);
                });
    }

    @Test
    void aHostBeanStillWinsOverTheFallback() {
        var custom = new WorkflowMetrics() {};
        metricsExcluded.withBean(WorkflowMetrics.class, () -> custom)
                .run(context -> assertThat(context.getBean(WorkflowMetrics.class)).isSameAs(custom));
    }
}
