package io.mateu.workflow.worker.kafka;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Wires the worker's Kafka bindings so a service only has to depend on this module and point at its
 * task topic. Two things are contributed:
 *
 * <ul>
 *   <li>{@code consumeWorkerEvent} is <b>added</b> to {@code spring.cloud.function.definition},
 *       merged with whatever the application already declares (so a service that also exposes its
 *       own functions keeps them). This wins over the application value on purpose — depending on
 *       worker-kafka means you want its consumer bound.</li>
 *   <li>default binding properties — the reply goes to {@code upstream}, the inbound group defaults
 *       to the application name, the inbound destination defaults to {@code downstream} — added at
 *       the back so the application overrides any of them (e.g. to name its own task topic).</li>
 * </ul>
 */
public class WorkerKafkaBindingDefaults implements EnvironmentPostProcessor, Ordered {

    static final String FUNCTION = "consumeWorkerEvent";
    static final String DEFINITION_KEY = "spring.cloud.function.definition";
    private static final String OVERRIDE = "eventconductor-worker-function-definition";
    private static final String DEFAULTS = "eventconductor-worker-binding-defaults";
    private static final String PREFIX = "spring.cloud.stream.bindings.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        if (sources.contains(OVERRIDE)) {
            return;
        }

        var existing = environment.getProperty(DEFINITION_KEY, "");
        sources.addFirst(new MapPropertySource(OVERRIDE, Map.of(DEFINITION_KEY, merge(existing))));

        var defaults = new LinkedHashMap<String, Object>();
        defaults.put(PREFIX + FUNCTION + "-in-0.destination", "downstream");
        defaults.put(PREFIX + FUNCTION + "-in-0.group", "${spring.application.name:eventconductor-worker}");
        defaults.put(PREFIX + "upstream.destination", "upstream");
        sources.addLast(new MapPropertySource(DEFAULTS, Map.copyOf(defaults)));
    }

    /** Append {@code consumeWorkerEvent} to a {@code ;}-separated definition, keeping order and uniqueness. */
    static String merge(String existing) {
        var functions = new LinkedHashSet<String>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(functions::add);
        }
        functions.add(FUNCTION);
        return String.join(";", functions);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
