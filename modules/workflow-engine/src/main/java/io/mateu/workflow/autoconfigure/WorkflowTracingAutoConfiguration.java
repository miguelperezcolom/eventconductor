package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Engine tracing, active only when Micrometer tracing is on the classpath AND the host application
 * provides a {@code Tracer} and a {@code Propagator} — which Spring Boot does once a tracing bridge
 * (say {@code micrometer-tracing-bridge-otel}) is present. Otherwise
 * {@link WorkflowEngineAutoConfiguration} falls back to the no-op {@link WorkflowTracing}, and the
 * engine runs with no observability dependencies at all.
 */
@AutoConfiguration(
        before = WorkflowEngineAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration")
@ConditionalOnClass({Tracer.class, Propagator.class})
@ConditionalOnBean({Tracer.class, Propagator.class})
public class WorkflowTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowTracing.class)
    WorkflowTracing workflowTracing(Tracer tracer, Propagator propagator) {
        return new MicrometerWorkflowTracing(tracer, propagator);
    }
}
