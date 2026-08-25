package io.mateu.workflow;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That this application can actually trace.
 *
 * <p>It declared `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`, mapped
 * TRACING_SAMPLING and OTLP_TRACING_ENDPOINT in its yaml, and produced no traces at all — because
 * those are the OpenTelemetry <em>libraries</em>, and what creates a {@link Tracer} is Spring
 * Boot's tracing <em>auto-configuration</em>, which Boot 4 split out of
 * `spring-boot-starter-actuator` into its own module. With no Tracer bean,
 * `WorkflowTracingAutoConfiguration` resolved its ObjectProvider to nothing and every call ran
 * untraced. Silently, which is why it shipped.
 *
 * <p>So this asserts the bean, not the configuration. The two properties are only worth having if
 * something reads them, and a yaml that names a property nobody owns looks exactly like one that
 * works.
 */
@SpringBootTest
class TracingWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void a_tracer_is_wired_so_the_engine_has_something_to_report_spans_to() {
        assertThat(context.getBeanProvider(Tracer.class).getIfAvailable())
                .as("a Tracer bean, without which the tracing configuration is decoration")
                .isNotNull();
    }

    @Test
    void the_tracer_is_the_opentelemetry_one_so_the_otlp_endpoint_means_something() {
        // Not just any Tracer: the yaml maps OTLP_TRACING_ENDPOINT, which only an OpenTelemetry
        // bridge can honour. Asserting the bean and its kind rather than reading the property back
        // — relaxed binding accepts a property nobody owns without complaint, so reading it proves
        // only that the yaml says what the yaml says.
        assertThat(context.getBeanProvider(Tracer.class).getIfAvailable())
                .as("an OpenTelemetry-backed tracer")
                .isInstanceOf(io.micrometer.tracing.otel.bridge.OtelTracer.class);
    }

    /**
     * The span exporter, which is the half that was missing.
     *
     * <p>A {@code Tracer} without an exporter builds spans and drops them — the two tests above
     * passed, in CI and in the deployed image, while no span had ever reached the collector. What
     * the exporter's existence depends on is the endpoint property being <em>bound</em>, and Boot 4
     * deprecated {@code management.otlp.tracing.endpoint} at level {@code error}: it reads back
     * perfectly from the environment and nothing consumes it, so no exporter was created.
     *
     * <p>Asserting the bean rather than reading the property back, for exactly that reason. Reading
     * a property back proves only that the yaml says what the yaml says — it is what made this look
     * configured for two releases.
     */
    @Test
    void a_span_exporter_exists_so_the_spans_go_somewhere() {
        assertThat(context.getBeanProvider(io.opentelemetry.sdk.trace.export.SpanExporter.class)
                .getIfAvailable())
                .as("an OTLP span exporter, without which every span is built and thrown away")
                .isNotNull();
    }
}
