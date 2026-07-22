package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.*;

/**
 * When workflow.mode=embedded, automatically excludes Kafka/Cloud Stream autoconfiguration classes
 * so the user does not have to list them manually in spring.autoconfigure.exclude.
 * When workflow.persistence=memory, JPA autoconfiguration is also excluded.
 */
public class EmbeddedModeAutoConfigurationExcluder implements EnvironmentPostProcessor, Ordered {

    private static final List<String> KAFKA_CLASSES = List.of(
            "org.springframework.cloud.stream.config.BindingServiceConfiguration",
            "org.springframework.cloud.stream.config.BindersHealthIndicatorAutoConfiguration",
            "org.springframework.cloud.stream.config.ChannelsEndpointAutoConfiguration",
            "org.springframework.cloud.stream.config.BindingsEndpointAutoConfiguration",
            "org.springframework.cloud.stream.function.FunctionConfiguration",
            "org.springframework.cloud.stream.binder.kafka.streams.KafkaStreamsBinderSupportAutoConfiguration",
            "org.springframework.cloud.stream.binder.kafka.streams.function.KafkaStreamsFunctionAutoConfiguration",
            "org.springframework.cloud.stream.binder.kafka.streams.ExtendedBindingHandlerMappingsProviderAutoConfiguration",
            "org.springframework.cloud.stream.binder.kafka.streams.endpoint.KafkaStreamsTopologyEndpointAutoConfiguration",
            "org.springframework.cloud.stream.binder.kafka.config.ExtendedBindingHandlerMappingsProviderConfiguration",
            "org.springframework.cloud.stream.binder.kafka.config.MessageConverterHelperConfiguration"
    );

    private static final List<String> JPA_CLASSES = List.of(
            // Spring Boot 4 relocated (and renamed) these auto-configuration classes into
            // per-technology modules. They are referenced by name, so a stale string would
            // silently stop excluding JPA in workflow.persistence=memory mode.
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"embedded".equals(environment.getProperty("workflow.mode", "embedded"))) return;

        String persistence = environment.getProperty("workflow.persistence", "memory");

        List<String> toExclude = new ArrayList<>();
        KAFKA_CLASSES.stream()
                .filter(c -> ClassUtils.isPresent(c, null))
                .forEach(toExclude::add);

        if ("memory".equals(persistence)) {
            JPA_CLASSES.stream()
                    .filter(c -> ClassUtils.isPresent(c, null))
                    .forEach(toExclude::add);
        }

        if (toExclude.isEmpty()) return;

        String[] existing = environment.getProperty("spring.autoconfigure.exclude", String[].class, new String[0]);
        Set<String> merged = new LinkedHashSet<>(Arrays.asList(existing));
        merged.addAll(toExclude);

        Map<String, Object> props = new HashMap<>();
        props.put("spring.autoconfigure.exclude", String.join(",", merged));
        environment.getPropertySources().addFirst(
                new MapPropertySource("workflow-engine-embedded-excludes", props)
        );
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
