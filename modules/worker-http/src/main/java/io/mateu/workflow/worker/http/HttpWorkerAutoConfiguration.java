package io.mateu.workflow.worker.http;

import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.Map;

/**
 * Registers the built-in {@code http-call@1} task wherever this module is on the classpath: the
 * transport (worker-embedded in the engine's pod, worker-kafka in a worker deployment) collects it
 * like any task. {@code workflow.http.enabled=false} leaves it out.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "workflow.http.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpWorkerProperties.class)
public class HttpWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HttpCallHandler httpCallHandler(HttpWorkerProperties properties, Environment environment,
                                           ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        var secrets = new SecretResolver(name -> {
            var configured = properties.getSecrets().get(name);
            return configured != null ? configured : environment.getProperty(name);
        });
        var guard = new HostGuard(properties.getAllowedHosts(), InetAddress::getAllByName);
        return new HttpCallHandler(properties, secrets, guard, metricsFor(registry));
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TaskRegistration httpCallTaskRegistration(HttpCallHandler handler, HttpWorkerProperties properties) {
        return new TaskRegistration<>("http-call", 1, properties.getTopic(), HttpCallHandler.Input.class,
                (Class<Map<String, Object>>) (Class) Map.class, handler);
    }

    private static HttpCallMetrics metricsFor(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        try {
            return (target, status, nanos) -> {
                var meters = registry.getIfAvailable();
                if (meters == null) return;
                io.micrometer.core.instrument.Timer.builder("eventconductor.http.calls")
                        .description("HTTP_CALL requests, by connection (or host) and status")
                        .tag("target", target == null ? "unknown" : target)
                        .tag("status", status)
                        .register(meters)
                        .record(java.time.Duration.ofNanos(nanos));
            };
        } catch (NoClassDefFoundError noMicrometer) {
            return HttpCallMetrics.NONE;
        }
    }
}
