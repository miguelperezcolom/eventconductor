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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpWorkerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public HttpCallHandler httpCallHandler(HttpWorkerProperties properties, Environment environment,
                                           ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        var secrets = new SecretResolver(secretLookup(properties, environment));
        var guard = new HostGuard(properties.getAllowedHosts(), InetAddress::getAllByName);
        return new HttpCallHandler(properties, secrets, guard, metricsFor(registry));
    }

    /**
     * {@code workflow.http.secrets} first; then — unless {@code workflow.http.secrets-from-environment}
     * is false — any environment variable or Spring property of that name.
     */
    static java.util.function.Function<String, String> secretLookup(HttpWorkerProperties properties,
                                                                    Environment environment) {
        if (properties.isSecretsFromEnvironment() && properties.getAllowedHosts().isEmpty()) {
            log.warn("workflow.http.secrets-from-environment is true and workflow.http.allowed-hosts is empty: "
                    + "a workflow definition author can reference ${secret:NAME} for ANY environment variable or "
                    + "Spring property of this process (database passwords, cloud credentials...) and send it to "
                    + "any public host. Set workflow.http.secrets-from-environment=false and list the secrets "
                    + "definitions may use under workflow.http.secrets, and/or restrict workflow.http.allowed-hosts.");
        }
        return name -> {
            var configured = properties.getSecrets().get(name);
            if (configured != null || !properties.isSecretsFromEnvironment()) {
                return configured;
            }
            return environment.getProperty(name);
        };
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
