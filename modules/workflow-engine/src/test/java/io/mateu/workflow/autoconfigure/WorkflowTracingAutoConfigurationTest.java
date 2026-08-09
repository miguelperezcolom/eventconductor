package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class WorkflowTracingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowTracingAutoConfiguration.class,
                    WorkflowTracingFallbackAutoConfiguration.class));

    @Test
    void withoutATracerDegradesToNoop() {
        // The tracing classes are on the classpath but no bridge is configured, so no Tracer bean
        // exists. The tracer is resolved lazily, so with none present every call resolves nothing and
        // runs the work untraced — the behaviour that matters, whatever the bean's concrete type.
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowTracing.class);
            var tracing = context.getBean(WorkflowTracing.class);
            var ran = new AtomicBoolean();
            assertThatCode(() -> {
                assertThat(tracing.currentTraceParent()).isNull();
                tracing.continuing("00-abc-def-01", "step", () -> ran.set(true));
                assertThat(tracing.span("dispatch", Map.of("k", "v"), () -> "done")).isEqualTo("done");
            }).doesNotThrowAnyException();
            assertThat(ran).isTrue();
        });
    }

    @Test
    void withATracerProvidesMicrometerImplementation() {
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowTracing.class);
                    assertThat(context.getBean(WorkflowTracing.class))
                            .isInstanceOf(MicrometerWorkflowTracing.class);
                });
    }

    @Test
    void micrometerImplementationWinsWhenBothConfigsAreComponentScanned() {
        // An application whose @SpringBootApplication sits at io.mateu.workflow scans this package and
        // registers both auto-configurations as plain @Configuration, where @AutoConfiguration(before=)
        // ordering is not honoured and the beans resolve in class-name order — WorkflowEngine before
        // WorkflowTracing — so the no-op used to register first and shadow the Micrometer bridge (and
        // the bridge's own @ConditionalOnBean(Tracer) ran before the Boot-created tracer existed).
        // Registering them as *user* configurations reproduces that. The no-op is now gated on the
        // Tracer class being absent, so with it present the Micrometer bridge wins whatever the order.
        new ApplicationContextRunner()
                .withUserConfiguration(WorkflowTracingAutoConfiguration.class, WorkflowTracingFallbackAutoConfiguration.class)
                .withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowTracing.class);
                    assertThat(context.getBean(WorkflowTracing.class))
                            .isInstanceOf(MicrometerWorkflowTracing.class);
                });
    }

    @Test
    void userDefinedWorkflowTracingBeanWins() {
        var custom = mock(WorkflowTracing.class);
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .withBean(WorkflowTracing.class, () -> custom)
                .run(context -> assertThat(context.getBean(WorkflowTracing.class)).isSameAs(custom));
    }
}
