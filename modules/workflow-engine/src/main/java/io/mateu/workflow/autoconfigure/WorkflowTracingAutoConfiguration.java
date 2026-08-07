package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Engine tracing, active when Micrometer tracing is on the classpath. The {@code Tracer} and
 * {@code Propagator} that Spring Boot creates once a tracing bridge (say
 * {@code micrometer-tracing-bridge-otel}) is present are looked up with {@link ObjectProvider} at
 * first <em>use</em>, not gated with {@code @ConditionalOnBean} at condition-<em>evaluation</em>
 * time: the tracer is built by a Boot auto-configuration, and this class needs to be ordered after
 * it — an ordering that was pinned by class name to
 * {@code org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration},
 * which Spring Boot 4 renamed. And when a host component-scans this package (its
 * {@code @SpringBootApplication} sits at {@code io.mateu.workflow}), the {@code @AutoConfiguration}
 * ordering is not honoured at all and {@code @ConditionalOnBean} runs before the tracer exists —
 * both leaving the condition negative and the bridge silently degraded to
 * {@link WorkflowTracing#NOOP}. Resolving lazily removes the ordering dependency; if the tracing
 * classes are present but no bridge is configured, {@link MicrometerWorkflowTracing} finds no tracer
 * and runs every call untraced.
 */
@AutoConfiguration(before = WorkflowEngineAutoConfiguration.class)
@ConditionalOnClass({Tracer.class, Propagator.class})
public class WorkflowTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowTracing.class)
    WorkflowTracing workflowTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        // The providers, not resolved beans: MicrometerWorkflowTracing resolves them on first use,
        // by when the tracer exists. Resolving here would run too early and pin the no-op forever.
        return new MicrometerWorkflowTracing(tracer, propagator);
    }
}
